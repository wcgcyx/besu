/*
 * Copyright ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.mainnet;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Hash;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.core.Wei;
import org.hyperledger.besu.ethereum.core.WorldState;
import org.hyperledger.besu.ethereum.core.WorldUpdater;
import org.hyperledger.besu.ethereum.core.fees.TransactionGasBudgetCalculator;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.trie.MerklePatriciaTrie;
import org.hyperledger.besu.ethereum.trie.Node;
import org.hyperledger.besu.ethereum.vm.BlockHashLookup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.ethereum.worldstate.StateTrieAccountValue;

public abstract class AbstractBlockProcessor implements BlockProcessor {
  @FunctionalInterface
  public interface TransactionReceiptFactory {

    TransactionReceipt create(
        TransactionProcessor.Result result, WorldState worldState, long gasUsed);
  }

  private static final Logger LOG = LogManager.getLogger();

  static final int MAX_GENERATION = 6;

  public static class Result implements BlockProcessor.Result {

    private static final AbstractBlockProcessor.Result FAILED =
        new AbstractBlockProcessor.Result(false, null);

    private final boolean successful;

    private final List<TransactionReceipt> receipts;

    public static AbstractBlockProcessor.Result successful(
        final List<TransactionReceipt> receipts) {
      return new AbstractBlockProcessor.Result(true, ImmutableList.copyOf(receipts));
    }

    public static AbstractBlockProcessor.Result failed() {
      return FAILED;
    }

    Result(final boolean successful, final List<TransactionReceipt> receipts) {
      this.successful = successful;
      this.receipts = receipts;
    }

    @Override
    public List<TransactionReceipt> getReceipts() {
      return receipts;
    }

    @Override
    public boolean isSuccessful() {
      return successful;
    }
  }

  private final TransactionProcessor transactionProcessor;

  private final AbstractBlockProcessor.TransactionReceiptFactory transactionReceiptFactory;

  final Wei blockReward;

  private final boolean skipZeroBlockRewards;

  private final MiningBeneficiaryCalculator miningBeneficiaryCalculator;

  private final TransactionGasBudgetCalculator gasBudgetCalculator;

  protected AbstractBlockProcessor(
      final TransactionProcessor transactionProcessor,
      final TransactionReceiptFactory transactionReceiptFactory,
      final Wei blockReward,
      final MiningBeneficiaryCalculator miningBeneficiaryCalculator,
      final boolean skipZeroBlockRewards,
      final TransactionGasBudgetCalculator gasBudgetCalculator) {
    this.transactionProcessor = transactionProcessor;
    this.transactionReceiptFactory = transactionReceiptFactory;
    this.blockReward = blockReward;
    this.miningBeneficiaryCalculator = miningBeneficiaryCalculator;
    this.skipZeroBlockRewards = skipZeroBlockRewards;
    this.gasBudgetCalculator = gasBudgetCalculator;
  }

  @Override
  public AbstractBlockProcessor.Result processBlock(
      final Blockchain blockchain,
      final MutableWorldState worldState,
      final BlockHeader blockHeader,
      final List<Transaction> transactions,
      final List<BlockHeader> ommers) {

    // Get a copy of the initial world state
    MutableWorldState initialWorldState = worldState.copy();
    // Start tracking
    worldState.startTracking();

    long gasUsed = 0;
    final List<TransactionReceipt> receipts = new ArrayList<>();

    for (final Transaction transaction : transactions) {
      final long remainingGasBudget = blockHeader.getGasLimit() - gasUsed;
      if (!gasBudgetCalculator.hasBudget(transaction, blockHeader, gasUsed)) {
        LOG.warn(
            "Transaction processing error: transaction gas limit {} exceeds available block budget remaining {}",
            transaction.getGasLimit(),
            remainingGasBudget);
        return AbstractBlockProcessor.Result.failed();
      }

      final WorldUpdater worldStateUpdater = worldState.updater();
      final BlockHashLookup blockHashLookup = new BlockHashLookup(blockHeader, blockchain);
      final Address miningBeneficiary =
          miningBeneficiaryCalculator.calculateBeneficiary(blockHeader);

      final TransactionProcessor.Result result =
          transactionProcessor.processTransaction(
              blockchain,
              worldStateUpdater,
              blockHeader,
              transaction,
              miningBeneficiary,
              blockHashLookup,
              true,
              TransactionValidationParams.processingBlock());
      if (result.isInvalid()) {
        return AbstractBlockProcessor.Result.failed();
      }

      worldStateUpdater.commit();
      gasUsed = transaction.getGasLimit() - result.getGasRemaining() + gasUsed;
      final TransactionReceipt transactionReceipt =
          transactionReceiptFactory.create(result, worldState, gasUsed);
      receipts.add(transactionReceipt);
    }

    if (!rewardCoinbase(worldState, blockHeader, ommers, skipZeroBlockRewards)) {
      return AbstractBlockProcessor.Result.failed();
    }

    // Get accessed code and storage tries.
    Map<Bytes32, Bytes> accessedCode = worldState.getAccessedCode();
    Map<Bytes32, MerklePatriciaTrie<Bytes32, Bytes>> accessedStorage = worldState.getAccessedStorage();
    // Stop tracking.
    worldState.stopTracking();

    // Replace updated storage tries with original storage tries
    for (Bytes32 storageHash : accessedStorage.keySet()) {
      MerklePatriciaTrie<Bytes32, Bytes> storageTrie = accessedStorage.get(storageHash);
      if (storageTrie.getRootHash() != storageHash) {
        // It has been updated during execution.
        MerklePatriciaTrie<Bytes32, Bytes> originalStorageTrie = worldState.getStorageTrieByHash(storageHash);
        List<Bytes32> loadedLeafPathList = storageTrie.getLoadedLeafPathList();
        // Try to load every leaf to the original storage trie.
        for (Bytes32 leaf : loadedLeafPathList) {
          originalStorageTrie.get(leaf);
        }
        // Now replace with the loaded original storage trie.
        accessedStorage.replace(storageHash, originalStorageTrie);
      }
    }

    // Try to load every leaf to original state trie
    MerklePatriciaTrie<Bytes32, Bytes> originalStateTrie = initialWorldState.getStateTrie();
    List<Bytes32> loadedLeafPathList = worldState.getStateTrie().getLoadedLeafPathList();
    for (Bytes32 leaf : loadedLeafPathList) {
      originalStateTrie.get(leaf);
    }

    // Generate witness
    Bytes witness = Bytes.concatenate(Bytes.of(0x01, 0x00), generateStateTrieWitness(originalStateTrie.getRoot(), worldState, accessedCode, accessedStorage, Bytes.EMPTY));
    // Currently only log the witness
    LOG.info("Witness: " + witness.size());

    worldState.persist();
    return AbstractBlockProcessor.Result.successful(receipts);
  }

  private Bytes generateStateTrieWitness(final Node<Bytes> node, MutableWorldState worldState, Map<Bytes32, Bytes> accessedCode, Map<Bytes32, MerklePatriciaTrie<Bytes32, Bytes>> accessedStorage, Bytes prvPath) {
    Bytes witness = Bytes.EMPTY;
    if (node.isHashNode()) {
      witness = Bytes.concatenate(witness,
              Bytes.of(0x03),
              node.getHash());
    } else if (node.isBranchNode()) {
      witness = Bytes.concatenate(witness,
              Bytes.of(0x00),
              node.getBitMask());
      List<Node<Bytes>> children = node.getChildren();
      for (int i = 0; i < 16; i++) {
        Node<Bytes> child = children.get(i);
        if (child.isNullNode()) continue;
        witness = Bytes.concatenate(witness,
                generateStateTrieWitness(child, worldState, accessedCode, accessedStorage, Bytes.concatenate(prvPath, Bytes.of(i))));
      }
    } else if (node.isExtensionNode()) {
      witness = Bytes.concatenate(witness,
              Bytes.of(0x01),
              Bytes.of(node.getPath().size()),
              node.getExtensionPath(),
              generateStateTrieWitness(node.getChildren().get(0), worldState, accessedCode, accessedStorage, Bytes.concatenate(prvPath, node.getPath())));
    } else if (node.isLeafNode()) {
      StateTrieAccountValue accountValue = StateTrieAccountValue.readFrom(RLP.input(node.getValue().get()));
      Bytes32 codeHash = accountValue.getCodeHash();
      Bytes32 leafPath = node.getLeafPath(prvPath);
      witness = Bytes.concatenate(witness,
              Bytes.of(0x02),
              Bytes.of(codeHash.equals(Hash.EMPTY) ? 0x00 : 0x01),
              leafPath,
              accountValue.getBalance().toBytes(),
              Bytes32.leftPad(Bytes.ofUnsignedLong(accountValue.getNonce())));
      if (!codeHash.equals(Hash.EMPTY)) {
        // Add code
        Bytes code = worldState.getCodeFromHash(codeHash);
        boolean codeAccessed = accessedCode.containsKey(codeHash);
        witness = Bytes.concatenate(witness,
                Bytes.of(codeAccessed ? 0x00 : 0x01),
                Bytes.ofUnsignedInt(code.size()),
                codeAccessed ? code : codeHash);
        // Add storage
        Bytes32 storageHash = accountValue.getStorageRoot();
        boolean storageAccessed = accessedStorage.containsKey(storageHash);
        witness = Bytes.concatenate(witness,
                storageAccessed ? generateStorageTrieWitness(accessedStorage.get(storageHash).getRoot(), Bytes.EMPTY) :
                Bytes.concatenate(Bytes.of(0x03), storageHash));
      }
    } else {
      // This should never happen
      LOG.error("State trie error with node: " + node.getRlp());
    }
    return witness;
  }

  private Bytes generateStorageTrieWitness(final Node<Bytes> node, final Bytes prvPath) {
    Bytes witness = Bytes.EMPTY;
    if (node.isStoredNode() || node.isNullNode()) {
      // This is a storage hash node or null node.
      witness = Bytes.concatenate(witness,
              Bytes.of(0x03),
              node.getHash());
    } else if (node.isBranchNode()) {
      witness = Bytes.concatenate(witness,
              Bytes.of(0x00),
              node.getBitMask());
      List<Node<Bytes>> children = node.getChildren();
      for (int i = 0; i < 16; i++) {
        Node<Bytes> child = children.get(i);
        if (child.isNullNode()) continue;
        witness = Bytes.concatenate(witness,
                generateStorageTrieWitness(child, Bytes.concatenate(prvPath, Bytes.of(i))));
      }
    } else if (node.isExtensionNode()) {
      witness = Bytes.concatenate(witness,
              Bytes.of(0x01),
              Bytes.of(node.getPath().size()),
              node.getExtensionPath(),
              generateStorageTrieWitness(node.getChildren().get(0), Bytes.concatenate(prvPath, node.getPath())));
    } else if (node.isLeafNode()) {
      witness = Bytes.concatenate(witness,
              Bytes.of(0x02),
              node.getLeafPath(prvPath),
              RLP.input(node.getValue().get()).readUInt256Scalar().toBytes());
    } else {
      // This should never happen
      LOG.error("Storage trie error with node: " + node.getRlp());
    }
    return witness;
  }

  protected MiningBeneficiaryCalculator getMiningBeneficiaryCalculator() {
    return miningBeneficiaryCalculator;
  }

  abstract boolean rewardCoinbase(
      final MutableWorldState worldState,
      final BlockHeader header,
      final List<BlockHeader> ommers,
      final boolean skipZeroBlockRewards);
}
