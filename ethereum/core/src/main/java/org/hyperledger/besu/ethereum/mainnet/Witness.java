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
import java.time.Duration;

public class Witness {

  // Error code is 0 if nothing goes wrong,
  // 1 if generation goes wrong,
  // 2 if verification(consumption) goes wrong.
  public static int error = 0;

  // Witness in bytes.
  public static Bytes data = null;

  // Interesting data.
  public static Duration creationTime = null;

  public static int stateTrieBranchNodes = 0;

  public static int stateTrieExtensionNodes = 0;

  public static int stateTrieHashNodes = 0;

  public static int stateTrieLeafNodes = 0;

  public static int stateTrieHashSize = 0;

  public static int stateTrieLeafSize = 0;

  public static int stateTrieLeafCode = 0;

  public static int storageTrieBranchNodes = 0;

  public static int storageTrieExtensionNodes = 0;

  public static int storageTrieHashNodes = 0;

  public static int storageTrieLeafNodes = 0;

  public static int storageTrieHashsize = 0;

  public static int storageTrieLeafSize = 0;

  public static void clear() {
    error = 0;
    data = null;
    creationTime = null;
    stateTrieBranchNodes = 0;
    stateTrieExtensionNodes = 0;
    stateTrieHashNodes = 0;
    stateTrieLeafNodes = 0;
    stateTrieHashSize = 0;
    stateTrieLeafSize = 0;
    stateTrieLeafCode = 0;
    storageTrieBranchNodes = 0;
    storageTrieExtensionNodes = 0;
    storageTrieHashNodes = 0;
    storageTrieLeafNodes = 0;
    storageTrieHashsize = 0;
    storageTrieLeafSize = 0;
  }
}
