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
package org.hyperledger.besu.ethereum.core;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.ethereum.trie.MerklePatriciaTrie;

import java.util.Map;

public interface MutableWorldState extends WorldState, MutableWorldView {

  /**
   * Creates an independent copy of this world state initially equivalent to this world state.
   *
   * @return a copy of this world state.
   */
  MutableWorldState copy();

  /** Persist accumulated changes to underlying storage. */
  void persist();

  default void startTracking() {}

  default Map<Bytes32, Bytes> getAccessedCode() { return null; }

  default Map<Bytes32, MerklePatriciaTrie<Bytes32, Bytes>> getAccessedStorage() { return null; }

  default void stopTracking() {}

  default MerklePatriciaTrie<Bytes32, Bytes> getStorageTrieByHash(Bytes32 storageHash) { return null; }

  default MerklePatriciaTrie<Bytes32, Bytes> getStateTrie() { return null; }

  default Bytes getCodeFromHash(final Bytes32 codeHash) { return null; }
}
