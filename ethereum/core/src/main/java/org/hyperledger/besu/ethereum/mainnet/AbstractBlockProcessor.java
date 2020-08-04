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
import org.hyperledger.besu.ethereum.core.Account;
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
import org.hyperledger.besu.ethereum.debug.TraceFrame;
import org.hyperledger.besu.ethereum.debug.TraceOptions;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.trie.Node;
import org.hyperledger.besu.ethereum.vm.BlockHashLookup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.ethereum.vm.DebugOperationTracer;
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

    long gasUsed = 0;
    final List<TransactionReceipt> receipts = new ArrayList<>();

    // Get a copy of the world state
    final MutableWorldState initialWorldState = worldState.copy();

    // Get operation tracer
    DebugOperationTracer tracer = new DebugOperationTracer(new TraceOptions(true, true, true));
    // Create a map to store accessed accounts and level of access
    Map<Address, Integer> accessedAddresses = new HashMap<>();

    for (final Transaction transaction : transactions) {

      // Add sender and recipient to accessed map
      Address senderAddress = transaction.getSender();
      accessedAddresses.put(senderAddress, accessedAddresses.getOrDefault(senderAddress, 0));
      Optional<Address> recipientAddress = transaction.getTo();
      recipientAddress.ifPresent(address -> {
        Account recipient = initialWorldState.get(address);
        if (recipient != null) {
          accessedAddresses.put(address, recipient.hasCode() ? 1 : accessedAddresses.getOrDefault(address, 0));
        }
      });

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
              TransactionValidationParams.processingBlock(),
              tracer);
      if (result.isInvalid()) {
        return AbstractBlockProcessor.Result.failed();
      }

      worldStateUpdater.commit();
      gasUsed = transaction.getGasLimit() - result.getGasRemaining() + gasUsed;
      final TransactionReceipt transactionReceipt =
          transactionReceiptFactory.create(result, worldState, gasUsed);
      receipts.add(transactionReceipt);
    }

    // Check trace frames to get more accessed accounts
    for (TraceFrame traceFrame : tracer.getTraceFrames()) {
      String opcode = traceFrame.getOpcode();
      Bytes32[] stack;
      Address address;
      switch (opcode) {
        case "BALANCE":
        case "SELFDESTRUCT":
        case "EXTCODEHASH":
          stack = traceFrame.getStack().get();
          if (stack.length < 1) break;
          address = Address.fromHexString(stack[stack.length - 1].toShortHexString());
          accessedAddresses.put(address, accessedAddresses.getOrDefault(address, 0));
          break;
        case "EXTCODECOPY":
        case "EXTCODESIZE":
          stack = traceFrame.getStack().get();
          if (stack.length < 1) break;
          address = Address.fromHexString(stack[stack.length - 1].toShortHexString());
          accessedAddresses.put(address, 1);
          break;
        case "CALL":
        case "CALLCODE":
        case "DELEGATECALL":
        case "STATICCALL":
          stack = traceFrame.getStack().get();
          if (stack.length < 2) break;
          address = Address.fromHexString(stack[stack.length - 2].toShortHexString());
          accessedAddresses.put(address, 1);
          break;
      }
    }

    if (!rewardCoinbase(worldState, blockHeader, ommers, skipZeroBlockRewards)) {
      return AbstractBlockProcessor.Result.failed();
    }

    Address coinbase = blockHeader.getCoinbase();
    accessedAddresses.put(coinbase, accessedAddresses.getOrDefault(coinbase, 0) > 0 ? 1 : 0);
    for (BlockHeader ommer : ommers) {
      Address ommerCoinbase = ommer.getCoinbase();
      accessedAddresses.put(ommerCoinbase, accessedAddresses.getOrDefault(ommerCoinbase, 0) > 0 ? 1 : 0);
    }

    // Remove any account that is created in the middle of the block
    accessedAddresses.keySet().removeIf(address -> initialWorldState.get(address) == null);
    // Convert addresses to path
    Map<Bytes32, Integer> accessedPath = accessedAddresses.entrySet()
            .stream()
            .collect(Collectors.toMap(e -> Hash.hash(e.getKey()),
                    Map.Entry::getValue));

    // Generate witness
    Node<Bytes> root = initialWorldState.getAccountStateTrieRoot();
    Bytes witness = Bytes.concatenate(Bytes.of(0x01, 0x00), generateStateTrieWitness(root, accessedPath, worldState, Bytes.EMPTY));
    // Currently only log the witness
    LOG.info("Witness: " + witness.toHexString());

    worldState.persist();
    return AbstractBlockProcessor.Result.successful(receipts);
  }

  private Bytes generateStateTrieWitness(final Node<Bytes> node, final Map<Bytes32, Integer> accessedPath, final MutableWorldState worldState, final Bytes prvPath) {
    Bytes witness = Bytes.EMPTY;
    if (node.isHashNode()) {
      witness = Bytes.concatenate(witness,
              Bytes.of(0x03),
              node.getHash());
    } else if (node.isBranchNode()) {
      witness = Bytes.concatenate(witness,
              Bytes.of(0x00),
              node.getBitMask());
      int i = 0;
      for (Node<Bytes> child : node.getChildren()) {
        if (child.isNullNode()) continue;
        witness = Bytes.concatenate(witness,
                generateStateTrieWitness(child, accessedPath, worldState, Bytes.concatenate(prvPath, Bytes.of(i++))));
      }
    } else if (node.isExtensionNode()) {
      witness = Bytes.concatenate(witness,
              Bytes.of(0x01),
              Bytes.of(node.getPath().size()),
              node.getExtensionPath(),
              generateStateTrieWitness(node.getChildren().get(0), accessedPath, worldState, Bytes.concatenate(prvPath, node.getPath())));
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
        Bytes code = worldState.getCodeFromHash(codeHash);
        int level = accessedPath.getOrDefault(leafPath, 0);
        witness = Bytes.concatenate(witness,
                Bytes.of(level == 0 ? 0x01 : 0x00),
                Bytes.ofUnsignedInt(code.size()),
                level == 0 ? codeHash : code,
                generateStorageTrieWitness(worldState.getAccountStorageTrieRoot(leafPath, accountValue.getStorageRoot()), Bytes.EMPTY));
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
      // This is a storage hash node.
      Bytes rlp = node.getRlp();
      Bytes hash = node.getHash();
      if (rlp.size() < hash.size()) {
        witness = Bytes.concatenate(witness,
                Bytes.of(0x00),
                Bytes.ofUnsignedShort(rlp.size()).trimLeadingZeros(),
                rlp);
      } else {
        witness = Bytes.concatenate(witness,
                hash.get(0) == 0 ? Bytes.of(0x00) : Bytes.EMPTY,
                hash);
      }
    } else if (node.isBranchNode()) {
      witness = Bytes.concatenate(witness,
              Bytes.of(0x00),
              node.getBitMask());
      int i = 0;
      for (Node<Bytes> child : node.getChildren()) {
        if (child.isNullNode()) continue;
        witness = Bytes.concatenate(witness,
                generateStorageTrieWitness(child, Bytes.concatenate(prvPath, Bytes.of(i++))));
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
