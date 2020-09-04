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

import org.apache.tuweni.bytes.Bytes32;

import java.util.HashSet;
import java.util.Set;

public class WitnessTracking {

  private static Set<Bytes32> loadedNodes = null;
  private static Set<Bytes32> loadedCode = null;
  private static Set<Bytes32> loadedStorage = null;

  public static void startTracking() {
    WitnessTracking.loadedNodes = new HashSet<>();
    WitnessTracking.loadedCode = new HashSet<>();
    WitnessTracking.loadedStorage = new HashSet<>();
  }

  public static void stopTracking() {
    WitnessTracking.loadedNodes = null;
    WitnessTracking.loadedCode = null;
    WitnessTracking.loadedStorage = null;
  }

  public static void addLoadedNode(final Bytes32 hash) {
    if (loadedNodes != null) loadedNodes.add(hash);
  }

  public static void addLoadedCode(final Bytes32 hash) {
    if (loadedCode != null) loadedCode.add(hash);
  }

  public static void addLoadedStorage(final Bytes32 hash) {
    if (loadedStorage != null) loadedStorage.add(hash);
  }

  public static Set<Bytes32> getLoadedNodes() {
    return loadedNodes;
  }

  public static Set<Bytes32> getLoadedCode() {
    return loadedCode;
  }

  public static Set<Bytes32> getLoadedStorage() {
    return loadedStorage;
  }
}