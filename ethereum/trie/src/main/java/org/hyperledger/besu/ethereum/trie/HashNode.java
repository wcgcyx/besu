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
package org.hyperledger.besu.ethereum.trie;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.ethereum.rlp.RLP;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class HashNode<V> implements Node<V> {

  private final Bytes32 hash;
  private final Bytes rlp;

  public HashNode(final Bytes32 hash) {
    this.hash = hash;
    this.rlp = null;
  }

  public HashNode(final Bytes rlp) {
    this.hash = null;
    this.rlp = rlp;
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
    return rlp == null ? RLP.encodeOne(getHash()) : rlp;
  }

  @Override
  public Bytes getRlpRef() {
    return rlp == null ? RLP.encodeOne(getHash()) : rlp;
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
    return "[Hash: " + hash.toHexString() + "]";
  }

  @Override
  public boolean isDirty() {
    return false;
  }

  @Override
  public void markDirty() {
    // do nothing
  }
}
