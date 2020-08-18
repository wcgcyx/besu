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
import org.hyperledger.besu.ethereum.trie.BranchNode;
import org.hyperledger.besu.ethereum.trie.ExtensionNode;
import org.hyperledger.besu.ethereum.trie.HashNode;
import org.hyperledger.besu.ethereum.trie.LeafNode;
import org.hyperledger.besu.ethereum.trie.NullNode;
import org.hyperledger.besu.ethereum.trie.Node;
import org.hyperledger.besu.ethereum.vm.BlockHashLookup;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    // To re-construct state trie from witness.
    // Bytes witness = Bytes.EMPTY;
    // Pair<Node<Bytes>, Integer> res = getStateTrieNode(witness, 2, Bytes.EMPTY);

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

    worldState.persist();
    return AbstractBlockProcessor.Result.successful(receipts);
  }

  public static class Pair<L, R> {

    public final L l;
    public final R r;

    public Pair (L l, R r) {
      this.l = l;
      this.r = r;
    }
  }

  private Pair<Node<Bytes>, Integer> getStateTrieNode(final Bytes witness, final int startingIndex, Bytes prvPath) {
    Node<Bytes> node = null;
    int pointer = startingIndex;
    byte nodeID = witness.get(pointer);
    pointer += 1;
    if (nodeID == 0x00) {
      //This is a branch node
      Bytes bitmask = witness.slice(pointer, 2);
      pointer += 2;
      ArrayList<Node<Bytes>> children = new ArrayList<>();
      for (int i = 0; i < 16; i++) {
        if (bitmask.and(i < 8 ? Bytes.of(0x01 << (7 - i), 0x00) : Bytes.of(0x00, 0x01 << (15 - i))).isZero()) {
          children.add(Node.newNullNode());
        } else {
          Pair<Node<Bytes>, Integer> res = getStateTrieNode(witness, pointer, Bytes.concatenate(prvPath, Bytes.of(i)));
          children.add(res.l);
          pointer = res.r;
        }
      }
      node = new BranchNode<>(children, Optional.empty(), null, null);
    } else if (nodeID == 0x01) {
      //This is a extension node
      int pathSize = witness.get(pointer);
      pointer += 1;
      Bytes path = Bytes.EMPTY;
      for (int i = 0; i < pathSize / 2; i++) {
        path = Bytes.concatenate(path, witness.slice(pointer, 1).shiftRight(4));
        path = Bytes.concatenate(path, witness.slice(pointer, 1).and(Bytes.of(0x0F)));
        pointer += 1;
      }
      if (pathSize % 2 == 1) {
        path = Bytes.concatenate(path, witness.slice(pointer, 1).shiftRight(4));
        pointer += 1;
      }
      Pair<Node<Bytes>, Integer> res = getStateTrieNode(witness, pointer, Bytes.concatenate(prvPath, path));
      pointer = res.r;
      node = new ExtensionNode<>(path, res.l, null);
    } else if (nodeID == 0x02) {
      //It is a leaf node.
      int accountType = witness.get(pointer);
      pointer += 1;
      Bytes fullPath = Bytes.EMPTY;
      for (int i = 0; i < 32; i++) {
        fullPath = Bytes.concatenate(fullPath, witness.slice(pointer, 1).shiftRight(4));
        fullPath = Bytes.concatenate(fullPath, witness.slice(pointer, 1).and(Bytes.of(0x0F)));
        pointer += 1;
      }
      if (fullPath.commonPrefixLength(prvPath) != prvPath.size()) {
        // This should never happen.
        LOG.error("Path error occurs.");
        System.exit(1);
      }
      // Add terminator
      Bytes path = Bytes.concatenate(fullPath.slice(prvPath.size()), Bytes.of(0x10));
      Wei balance = Wei.of(witness.slice(pointer, 32).toUnsignedBigInteger());
      pointer += 32;
      long nonce = witness.slice(pointer, 32).trimLeadingZeros().toLong();
      pointer += 32;
      Hash codeHash = Hash.EMPTY;
      Hash storageHash = Hash.EMPTY_TRIE_HASH;
      if (accountType == 1) {
        int codeType = witness.get(pointer);
        pointer += 1;
        int codeLength = witness.slice(pointer, 4).toInt();
        pointer += 4;
        if (codeType == 1) {
          codeHash = Hash.wrap(Bytes32.wrap(witness.slice(pointer, 32)));
          pointer += 32;
        } else {
          // TODO need to store the code to the world itself.
          codeHash = Hash.hash(witness.slice(pointer, codeLength));
          pointer += codeLength;
        }
        Pair<Node<Bytes>, Integer> storagePair = getStorageTrieNode(witness, pointer, Bytes.EMPTY);
        // TODO need to store the storage to the world itself.
        storageHash = Hash.wrap(storagePair.l.getHash());
        pointer = storagePair.r;
      }
      StateTrieAccountValue accountValue = new StateTrieAccountValue(nonce, balance, storageHash, codeHash);
      node = new LeafNode<>(path, RLP.encode(accountValue::writeTo), null, b -> b);
    } else if (nodeID == 0x03) {
      //It is a hash node
      node = new HashNode<>(Bytes32.wrap(witness.slice(pointer, 32)));
      pointer += 32;
    } else {
      //This should never happen
      LOG.error("Error happens.");
      System.exit(1);
    }
    return new Pair<>(node, pointer);
  }

  private Pair<Node<Bytes>, Integer> getStorageTrieNode(final Bytes witness, final int startingIndex, Bytes prvPath) {
    Node<Bytes> node = null;
    int pointer = startingIndex;
    byte nodeID = witness.get(pointer);
    pointer += 1;
    if (nodeID == 0x00) {
      //This is a branch node
      Bytes bitmask = witness.slice(pointer, 2);
      pointer += 2;
      ArrayList<Node<Bytes>> children = new ArrayList<>();
      for (int i = 0; i < 16; i++) {
        if (bitmask.and(i < 8 ? Bytes.of(0x01 << (7 - i), 0x00) : Bytes.of(0x00, 0x01 << (15 - i))).isZero()) {
          children.add(Node.newNullNode());
        } else {
          Pair<Node<Bytes>, Integer> res = getStorageTrieNode(witness, pointer, Bytes.concatenate(prvPath, Bytes.of(i)));
          children.add(res.l);
          pointer = res.r;
        }
      }
      node = new BranchNode<>(children, Optional.empty(), null, null);
    } else if (nodeID == 0x01) {
      //This is a extension node
      int pathSize = witness.get(pointer);
      pointer += 1;
      Bytes path = Bytes.EMPTY;
      for (int i = 0; i < pathSize / 2; i++) {
        path = Bytes.concatenate(path, witness.slice(pointer, 1).shiftRight(4));
        path = Bytes.concatenate(path, witness.slice(pointer, 1).and(Bytes.of(0x0F)));
        pointer += 1;
      }
      if (pathSize % 2 == 1) {
        path = Bytes.concatenate(path, witness.slice(pointer, 1).shiftRight(4));
        pointer += 1;
      }
      Pair<Node<Bytes>, Integer> res = getStorageTrieNode(witness, pointer, Bytes.concatenate(prvPath, path));
      pointer = res.r;
      node = new ExtensionNode<>(path, res.l, null);
    } else if (nodeID == 0x02) {
      //It is a leaf node.
      Bytes fullPath = Bytes.EMPTY;
      for (int i = 0; i < 32; i++) {
        fullPath = Bytes.concatenate(fullPath, witness.slice(pointer, 1).shiftRight(4));
        fullPath = Bytes.concatenate(fullPath, witness.slice(pointer, 1).and(Bytes.of(0x0F)));
        pointer += 1;
      }
      if (fullPath.commonPrefixLength(prvPath) != prvPath.size()) {
        // This should never happen.
        LOG.error("Path error occurs.");
        System.exit(1);
      }
      Bytes path = Bytes.concatenate(fullPath.slice(prvPath.size()), Bytes.of(0x10));
      Bytes value = RLP.encodeOne(witness.slice(pointer, 32).trimLeadingZeros());
      pointer += 32;
      node = new LeafNode<>(path, value, null, b -> b);
    } else if (nodeID == 0x03) {
      //This is a hash node or null node.
      Bytes hash = witness.slice(pointer, 32);
      pointer += 32;
      if (hash.equals(Hash.EMPTY_TRIE_HASH)) {
        node = new NullNode<>();
      } else {
        node = new HashNode<>(Bytes32.wrap(hash));
      }
    } else {
      //This should never happen
      LOG.error("Error happens.");
      System.exit(1);
    }
    return new Pair<>(node, pointer);
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
