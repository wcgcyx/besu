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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.Account;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.Hash;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.core.Wei;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.trie.MerklePatriciaTrie;
import org.hyperledger.besu.ethereum.trie.Node;
import org.hyperledger.besu.ethereum.trie.SimpleMerklePatriciaTrie;
import org.hyperledger.besu.ethereum.trie.StoredMerklePatriciaTrie;
import org.hyperledger.besu.ethereum.trie.WitnessTracking;
import org.hyperledger.besu.ethereum.worldstate.InMemoryMutableWorldState;
import org.hyperledger.besu.ethereum.worldstate.StateTrieAccountValue;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WitnessGenerator {

  private static final Logger LOG = LogManager.getLogger();

  public static void generateWitness(final BlockProcessor blockProcessor, final Blockchain blockchain,
                                        final MutableWorldState worldState, final Block block) {
    Instant timeStart = Instant.now();

    // Get a copy of the initial world state
    MutableWorldState initialWorldState = worldState.copy();

    // Start tracking
    WitnessTracking.startTracking();

    LOG.info("Start processing block.");

    // Process block
    if (!blockProcessor.processBlock(blockchain, worldState, block).isSuccessful()) {
      Witness.error = 1;
      return;
    }

    LOG.info("Start generating witness.");

    // Obtain tracking result
    Set<Bytes32> loadedNodes = WitnessTracking.getLoadedNodes();
    Set<Bytes32> loadedCode = WitnessTracking.getLoadedCode();
    Set<Bytes32> loadedStorage = WitnessTracking.getLoadedStorage();
    // TODO: It is possible for different tries loading a same node.
    //  Thus a same node could be loaded several times in a block, but it should be rare.
    //  It maybe interesting to see how often this happens.

    // Stop tracking
    WitnessTracking.stopTracking();

    // Process initial state trie
    Node<Bytes> root = initialWorldState.getAccountStateTrie().getRoot();
    loadFromNode(root, loadedNodes);

    // Process code and storage trie
    WorldStateStorage worldStateStorage = initialWorldState.getWorldStateStorage();

    Map<Bytes32, Bytes> accessedCode = new HashMap<>();
    for (Bytes32 hash : loadedCode) {
      accessedCode.put(hash, worldStateStorage.getCode(hash).orElse(Bytes.EMPTY));
    }

    Map<Bytes32, MerklePatriciaTrie<Bytes32, Bytes>> accessedStorage = new HashMap<>();
    for (Bytes32 hash : loadedStorage) {
      MerklePatriciaTrie<Bytes32, Bytes> storageTrie = new StoredMerklePatriciaTrie<>(
              worldStateStorage::getAccountStateTrieNode, hash, b -> b, b -> b);
      loadFromNode(storageTrie.getRoot(), loadedNodes);
      accessedStorage.put(hash, storageTrie);
    }

    // Generate witness
    int witness;
    try {
      witness = 2 + generateStateTrieWitness(root, worldStateStorage, accessedCode, accessedStorage, Bytes.EMPTY);
    } catch (Exception e) {
      Witness.error = 2;
      Witness.errorMsg = " " + e;
      return;
    }

    // Get creation time
    Witness.creationTime = Duration.between(timeStart, Instant.now());

    // Verify witness
//    try {
//      Map<Bytes32, Bytes> accessedCodeVerify = new HashMap<>();
//      Map<Bytes32, MerklePatriciaTrie<Bytes32, Bytes>> accessedStorageVerify = new HashMap<>();
//      Pair<Node<Bytes>, Integer> res = getStateTrieNode(witness, 2, Bytes.EMPTY, accessedCodeVerify, accessedStorageVerify);
//      MutableWorldState worldStateVerify = new InMemoryMutableWorldState(new SimpleMerklePatriciaTrie<>(b -> b, res.l), accessedCodeVerify, accessedStorageVerify);
//      if (!blockProcessor.processBlock(blockchain, worldStateVerify, block).isSuccessful()) {
//        Witness.error = 3;
//        return;
//      }
//      if (!worldStateVerify.rootHash().equals(worldState.rootHash())) {
//        Witness.error = 4;
//        return;
//      }
//    } catch (Exception e) {
//      Witness.error = 5;
//      Witness.errorMsg += e;
//      return;
//    }

    Witness.error = 0;
    Witness.data = witness;
  }

  private static void loadFromNode(final Node<Bytes> node, final Set<Bytes32> loadedNodes) {
    if (node.isHashNode()) {
      Bytes32 hash = node.getHash();
      if (loadedNodes.contains(hash)) {
        node.load();
        for (Node<Bytes> child : node.getChildren()) {
          loadFromNode(child, loadedNodes);
        }
      }
    }
  }

  private static int generateStateTrieWitness(final Node<Bytes> node, final WorldStateStorage worldStateStorage, final Map<Bytes32, Bytes> accessedCode,
                                                final Map<Bytes32, MerklePatriciaTrie<Bytes32, Bytes>> accessedStorage, final Bytes prvPath) throws Exception {
    int witness = 0;
    if (node.isHashNode()) {
      Witness.stateTrieHashNodes += 1;
      Witness.stateTrieHashSize += 32;
      witness = witness + 1 + 32;
    } else if (node.isBranchNode()) {
      Witness.stateTrieBranchNodes += 1;
      witness = witness + 1 + 2;
      List<Node<Bytes>> children = node.getChildren();
      for (int i = 0; i < 16; i++) {
        Node<Bytes> child = children.get(i);
        if (child.isNullNode()) continue;
        witness = witness + generateStateTrieWitness(child, worldStateStorage, accessedCode, accessedStorage, Bytes.concatenate(prvPath, Bytes.of(i)));
      }
    } else if (node.isExtensionNode()) {
      Witness.stateTrieExtensionNodes += 1;
      witness = witness + 1 + 1 + node.getExtensionPath().size() +
              generateStateTrieWitness(node.getChildren().get(0), worldStateStorage, accessedCode, accessedStorage, Bytes.concatenate(prvPath, node.getPath()));
    } else if (node.isLeafNode()) {
      int startPoint = witness;
      Witness.stateTrieLeafNodes += 1;
      // TODO: Currently, it uses full leaf path rather than account address.
      StateTrieAccountValue accountValue = StateTrieAccountValue.readFrom(RLP. input(node.getValue().get()));
      Bytes32 codeHash = accountValue.getCodeHash();
      Bytes32 storageHash = accountValue.getStorageRoot();
      Bytes32 leafPath = node.getLeafPath(prvPath);
      boolean isEOA = codeHash.equals(Hash.EMPTY) && storageHash.equals(Hash.EMPTY_TRIE_HASH);
      witness = witness + 1 + 1 + 32 + 32 + 32;
      if (!isEOA) {
        // Add code
        Bytes code = worldStateStorage.getCode(codeHash).orElse(Bytes.EMPTY);
        boolean codeAccessed = accessedCode.containsKey(codeHash);
        if (codeAccessed) Witness.stateTrieLeafCode += code.size();
        witness = witness + 1 + 4 + (codeAccessed ? code.size() : 32);
        // Add storage
        boolean storageAccessed = accessedStorage.containsKey(storageHash);
        witness = witness + (storageAccessed ? generateStorageTrieWitness(accessedStorage.get(storageHash).getRoot(), Bytes.EMPTY) :
                33);
      }
      Witness.stateTrieLeafSize += witness - startPoint;
    } else {
      // This should never happen
      Witness.errorMsg += "-1-";
      throw new NullPointerException();
    }
    return witness;
  }

  private static int generateStorageTrieWitness(final Node<Bytes> node, final Bytes prvPath) throws Exception {
    int witness = 0;
    if (node.isHashNode() || node.isNullNode()) {
      Witness.storageTrieHashNodes += 1;
      Witness.storageTrieHashsize += 32;
      // This is a storage hash node or null node
      // TODO: Currently, it does not care for RLP
      witness = witness + 33;
    } else if (node.isBranchNode()) {
      Witness.storageTrieBranchNodes += 1;
      witness = witness + 1 + 2;
      List<Node<Bytes>> children = node.getChildren();
      for (int i = 0; i < 16; i++) {
        Node<Bytes> child = children.get(i);
        if (child.isNullNode()) continue;
        witness = witness + generateStorageTrieWitness(child, Bytes.concatenate(prvPath, Bytes.of(i)));
      }
    } else if (node.isExtensionNode()) {
      Witness.storageTrieExtensionNodes += 1;
      witness = witness + 1 + 1 + node.getExtensionPath().size() +
              generateStorageTrieWitness(node.getChildren().get(0), Bytes.concatenate(prvPath, node.getPath()));
    } else if (node.isLeafNode()) {
      Witness.storageTrieLeafNodes += 1;
      int startPoint = witness;
      witness = witness + 1 + 32 + 32;
      Witness.storageTrieLeafSize += (witness - startPoint);
    } else {
      // This should never happen
      Witness.errorMsg += "-2-";
      throw new NullPointerException();
    }
    return witness;
  }

  private static class Pair<L, R> {

    public final L l;
    public final R r;

    public Pair (final L l, final R r) {
      this.l = l;
      this.r = r;
    }
  }

  private static Pair<Node<Bytes>, Integer> getStateTrieNode(final Bytes witness, final int startingIndex, final Bytes prvPath,
                                                             final Map<Bytes32, Bytes> accessedCode, final Map<Bytes32, MerklePatriciaTrie<Bytes32, Bytes>> accessedStorage) {
    Node<Bytes> node = null;
    int pointer = startingIndex;
    byte nodeID = witness.get(pointer);
    pointer += 1;
    if (nodeID == 0x00) {
      Witness.stateTrieBranchNodes += 1;
      //This is a branch node
      Bytes bitmask = witness.slice(pointer, 2);
      pointer += 2;
      ArrayList<Node<Bytes>> children = new ArrayList<>();
      for (int i = 0; i < 16; i++) {
        if (bitmask.and(i < 8 ? Bytes.of(0x01 << (7 - i), 0x00) : Bytes.of(0x00, 0x01 << (15 - i))).isZero()) {
          children.add(Node.newNullNode());
        } else {
          Pair<Node<Bytes>, Integer> res = getStateTrieNode(witness, pointer, Bytes.concatenate(prvPath, Bytes.of(i)), accessedCode, accessedStorage);
          children.add(res.l);
          pointer = res.r;
        }
      }
      node = Node.newBranchNode(children);
    } else if (nodeID == 0x01) {
      //This is a extension node
      Witness.stateTrieExtensionNodes += 1;
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
      Pair<Node<Bytes>, Integer> res = getStateTrieNode(witness, pointer, Bytes.concatenate(prvPath, path), accessedCode, accessedStorage);
      pointer = res.r;
      node = Node.newExtensionNode(path, res.l);
    } else if (nodeID == 0x02) {
      int startPointer = pointer;
      Witness.stateTrieLeafNodes += 1;
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
        Witness.errorMsg += "-3-";
        return null;
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
          Bytes code = witness.slice(pointer, codeLength);
          codeHash = Hash.hash(code);
          accessedCode.put(codeHash, code);
          pointer += codeLength;
          Witness.stateTrieLeafCode += codeLength;
        }
        Pair<Node<Bytes>, Integer> storagePair = getStorageTrieNode(witness, pointer, Bytes.EMPTY);
        storageHash = Hash.wrap(storagePair.l.getHash());
        accessedStorage.put(storageHash, new SimpleMerklePatriciaTrie<>(b -> b, storagePair.l));
        pointer = storagePair.r;
      }
      StateTrieAccountValue accountValue = new StateTrieAccountValue(nonce, balance, storageHash, codeHash, Account.DEFAULT_VERSION);
      node = Node.newLeafNode(path, RLP.encode(accountValue::writeTo));
      Witness.stateTrieLeafSize += (pointer - startPointer);
    } else if (nodeID == 0x03) {
      Witness.stateTrieHashNodes += 1;
      Witness.stateTrieHashSize += 32;
      //It is a hash node
      node = Node.newHashNode(Bytes32.wrap(witness.slice(pointer, 32)));
      pointer += 32;
    } else {
      //This should never happen
      Witness.errorMsg += "-4-";
      return null;
    }
    return new Pair<>(node, pointer);
  }

  private static Pair<Node<Bytes>, Integer> getStorageTrieNode(final Bytes witness, final int startingIndex, final Bytes prvPath) {
    Node<Bytes> node = null;
    int pointer = startingIndex;
    byte nodeID = witness.get(pointer);
    pointer += 1;
    if (nodeID == 0x00) {
      Witness.storageTrieBranchNodes += 1;
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
      node = Node.newBranchNode(children);
    } else if (nodeID == 0x01) {
      Witness.storageTrieExtensionNodes += 1;
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
      node = Node.newExtensionNode(path, res.l);
    } else if (nodeID == 0x02) {
      int startPointer = pointer;
      Witness.storageTrieLeafNodes += 1;
      //It is a leaf node.
      Bytes fullPath = Bytes.EMPTY;
      for (int i = 0; i < 32; i++) {
        fullPath = Bytes.concatenate(fullPath, witness.slice(pointer, 1).shiftRight(4));
        fullPath = Bytes.concatenate(fullPath, witness.slice(pointer, 1).and(Bytes.of(0x0F)));
        pointer += 1;
      }
      if (fullPath.commonPrefixLength(prvPath) != prvPath.size()) {
        // This should never happen.
        Witness.errorMsg += "-5-";
        return null;
      }
      Bytes path = Bytes.concatenate(fullPath.slice(prvPath.size()), Bytes.of(0x10));
      Bytes value = RLP.encodeOne(witness.slice(pointer, 32).trimLeadingZeros());
      pointer += 32;
      node = Node.newLeafNode(path, value);
      Witness.storageTrieLeafSize += (pointer - startPointer);
    } else if (nodeID == 0x03) {
      Witness.storageTrieHashNodes += 1;
      Witness.storageTrieHashsize += 32;
      //This is a hash node or null node.
      Bytes hash = witness.slice(pointer, 32);
      pointer += 32;
      if (hash.equals(Hash.EMPTY_TRIE_HASH)) {
        node = Node.newNullNode();
      } else {
        node = Node.newHashNode(Bytes32.wrap(hash));
      }
    } else {
      //This should never happen
      Witness.errorMsg += "-6-";
      return null;
    }
    return new Pair<>(node, pointer);
  }
}
