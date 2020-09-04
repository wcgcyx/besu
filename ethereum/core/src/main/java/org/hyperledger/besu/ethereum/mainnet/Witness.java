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

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.apache.tuweni.bytes.Bytes;

@JsonPropertyOrder({"data", "error"})
public class Witness {

  // Error code is 0 if nothing goes wrong,
  // 1 if generation goes wrong,
  // 2 if verification(consumption) goes wrong.
  public int error;

  // Witness in bytes.
  public Bytes data;

  public Witness(final int error, final Bytes data) {
    this.error = error;
    this.data = data;
  }

  @JsonGetter(value = "data")
  public String getData() {
    return data.toHexString();
  }

  @JsonGetter(value = "error")
  public int getError() {
    return error;
  }
}
