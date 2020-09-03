package org.hyperledger.besu.ethereum.trie;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.ethereum.rlp.RLP;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class HashNode<V> implements Node<V> {

  private final Bytes32 hash;

  public HashNode(final Bytes32 hash) {
    this.hash = hash;
  }

  @Override
  public Node<V> accept(final PathNodeVisitor<V> visitor, final Bytes path) {
    return null;
  }

  @Override
  public void accept(final NodeVisitor<V> visitor) {
    // do nothing
  }

  @Override
  public Bytes getPath() {
    return Bytes.EMPTY;
  }

  @Override
  public Optional<V> getValue() {
    return Optional.empty();
  }

  @Override
  public List<Node<V>> getChildren() {
    return Collections.emptyList();
  }

  @Override
  public Bytes getRlp() {
    return RLP.encodeOne(getHash());
  }

  @Override
  public Bytes getRlpRef() {
    return RLP.encodeOne(getHash());
  }

  @Override
  public Bytes32 getHash() {
    return hash;
  }

  @Override
  public Node<V> replacePath(final Bytes path) {
    return this;
  }

  @Override
  public String print() {
    return "[Hash: " + (hash == null ? "null" : hash.toHexString()) + "]";
  }

  @Override
  public boolean isDirty() {
    return false;
  }

  @Override
  public void markDirty() {
    // do nothing
  }

  @Override
  public Node<V> copy() {
    return new HashNode<>(hash);
  }
}