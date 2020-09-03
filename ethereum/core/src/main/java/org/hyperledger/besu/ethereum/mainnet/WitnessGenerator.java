package org.hyperledger.besu.ethereum.mainnet;

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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WitnessGenerator {

  public static Witness generateWitness(final BlockProcessor blockProcessor, final Blockchain blockchain,
                                        final MutableWorldState worldState, final Block block) {
    // Get a copy of the initial world state
    MutableWorldState initialWorldState = worldState.copy();

    // Start tracking
    WitnessTracking.startTracking();

    // Process block
    blockProcessor.processBlock(blockchain, worldState, block);

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
    Bytes witness;
    try {
      witness = Bytes.concatenate(Bytes.of(0x01, 0x00), generateStateTrieWitness(root, worldStateStorage, accessedCode, accessedStorage, Bytes.EMPTY));
    } catch (Exception e) {
      return new Witness(1, Bytes.EMPTY);
    }

    // Verify witness
    try {
      Map<Bytes32, Bytes> accessedCodeVerify = new HashMap<>();
      Map<Bytes32, MerklePatriciaTrie<Bytes32, Bytes>> accessedStorageVerify = new HashMap<>();
      Pair<Node<Bytes>, Integer> res = getStateTrieNode(witness, 2, Bytes.EMPTY, accessedCode, accessedStorage);
      MutableWorldState worldStateVerify = new InMemoryMutableWorldState(new SimpleMerklePatriciaTrie<>(b -> b, res.l), accessedCodeVerify, accessedStorageVerify);
      blockProcessor.processBlock(blockchain, worldStateVerify, block);
      if (!worldStateVerify.rootHash().equals(worldState.rootHash())) {
        return new Witness(2, Bytes.EMPTY);
      }
    } catch (Exception e) {
      return new Witness(2, Bytes.EMPTY);
    }

    return new Witness(0, witness);
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

  private static Bytes generateStateTrieWitness(final Node<Bytes> node, final WorldStateStorage worldStateStorage, final Map<Bytes32, Bytes> accessedCode,
                                                final Map<Bytes32, MerklePatriciaTrie<Bytes32, Bytes>> accessedStorage, final Bytes prvPath) {
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
                generateStateTrieWitness(child, worldStateStorage, accessedCode, accessedStorage, Bytes.concatenate(prvPath, Bytes.of(i))));
      }
    } else if (node.isExtensionNode()) {
      witness = Bytes.concatenate(witness,
              Bytes.of(0x01),
              Bytes.of(node.getPath().size()),
              node.getExtensionPath(),
              generateStateTrieWitness(node.getChildren().get(0), worldStateStorage, accessedCode, accessedStorage, Bytes.concatenate(prvPath, node.getPath())));
    } else if (node.isLeafNode()) {
      // TODO: Currently, it uses full leaf path rather than account address.
      StateTrieAccountValue accountValue = StateTrieAccountValue.readFrom(RLP. input(node.getValue().get()));
      Bytes32 codeHash = accountValue.getCodeHash();
      Bytes32 storageHash = accountValue.getStorageRoot();
      Bytes32 leafPath = node.getLeafPath(prvPath);
      boolean isEOA = codeHash.equals(Hash.EMPTY) && storageHash.equals(Hash.EMPTY_TRIE_HASH);
      witness = Bytes.concatenate(witness,
              Bytes.of(0x02),
              Bytes.of(isEOA ? 0x00 : 0x01),
              leafPath,
              accountValue.getBalance().toBytes(),
              Bytes32.leftPad(Bytes.ofUnsignedLong(accountValue.getNonce())));
      if (!isEOA) {
        // Add code
        Bytes code = worldStateStorage.getCode(codeHash).orElse(Bytes.EMPTY);
        boolean codeAccessed = accessedCode.containsKey(codeHash);
        witness = Bytes.concatenate(witness,
                Bytes.of(codeAccessed ? 0x00 : 0x01),
                Bytes.ofUnsignedInt(code.size()),
                codeAccessed ? code : codeHash);
        // Add storage
        boolean storageAccessed = accessedStorage.containsKey(storageHash);
        witness = Bytes.concatenate(witness,
                storageAccessed ? generateStorageTrieWitness(accessedStorage.get(storageHash).getRoot(), Bytes.EMPTY) :
                        Bytes.concatenate(Bytes.of(0x03), storageHash));
      }
    } else {
      // This should never happen
      return null;
    }
    return witness;
  }

  private static Bytes generateStorageTrieWitness(final Node<Bytes> node, final Bytes prvPath) {
    Bytes witness = Bytes.EMPTY;
    if (node.isHashNode() || node.isNullNode()) {
      // This is a storage hash node or null node
      // TODO: Currently, it does not care for RLP
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
      return null;
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
        }
        Pair<Node<Bytes>, Integer> storagePair = getStorageTrieNode(witness, pointer, Bytes.EMPTY);
        storageHash = Hash.wrap(storagePair.l.getHash());
        accessedStorage.put(storageHash, new SimpleMerklePatriciaTrie<>(b -> b, storagePair.l));
        pointer = storagePair.r;
      }
      StateTrieAccountValue accountValue = new StateTrieAccountValue(nonce, balance, storageHash, codeHash, Account.DEFAULT_VERSION);
      node = Node.newLeafNode(path, RLP.encode(accountValue::writeTo));
    } else if (nodeID == 0x03) {
      //It is a hash node
      node = Node.newHashNode(Bytes32.wrap(witness.slice(pointer, 32)));
      pointer += 32;
    } else {
      //This should never happen
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
      //It is a leaf node.
      Bytes fullPath = Bytes.EMPTY;
      for (int i = 0; i < 32; i++) {
        fullPath = Bytes.concatenate(fullPath, witness.slice(pointer, 1).shiftRight(4));
        fullPath = Bytes.concatenate(fullPath, witness.slice(pointer, 1).and(Bytes.of(0x0F)));
        pointer += 1;
      }
      if (fullPath.commonPrefixLength(prvPath) != prvPath.size()) {
        // This should never happen.
        return null;
      }
      Bytes path = Bytes.concatenate(fullPath.slice(prvPath.size()), Bytes.of(0x10));
      Bytes value = RLP.encodeOne(witness.slice(pointer, 32).trimLeadingZeros());
      pointer += 32;
      node = Node.newLeafNode(path, value);
    } else if (nodeID == 0x03) {
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
      return null;
    }
    return new Pair<>(node, pointer);
  }
}
