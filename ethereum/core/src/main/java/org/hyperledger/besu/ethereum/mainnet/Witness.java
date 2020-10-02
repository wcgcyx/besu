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

@JsonPropertyOrder({
        "error",
        "data",
        "stateRootBeforeWitnessGeneration",
        "stateRootAfterWitnessGeneration",
        "stateRootFromWitness",
        "stateRootAfterWitnessConsumption"
})
public class Witness {

  // Error code is 0 if nothing goes wrong,
  // 1 if generation goes wrong,
  // 2 if verification(consumption) goes wrong.
  public int error;

  // Witness in bytes.
  public Bytes data;

  // State Trie Root before executing the given block.
  public Bytes stateRootBeforeBlockExecution;

  // State Trie Root after executing the given block.
  public Bytes stateRootAfterBlockExecution;

  // State Trie Root constructed from the witness generated.
  public Bytes stateRootFromWitness;

  // State Trie Root after consuming the given witness.
  public Bytes stateRootAfterWitnessConsumption;

  public Witness(final int error,
                 final Bytes data,
                 final Bytes stateRootBeforeBlockExecution,
                 final Bytes stateRootAfterBlockExecution,
                 final Bytes stateRootFromWitness,
                 final Bytes stateRootAfterWitnessConsumption) {
    this.error = error;
    this.data = data;
    this.stateRootBeforeBlockExecution = stateRootBeforeBlockExecution;
    this.stateRootAfterBlockExecution = stateRootAfterBlockExecution;
    this.stateRootFromWitness = stateRootFromWitness;
    this.stateRootAfterWitnessConsumption = stateRootAfterWitnessConsumption;
  }

  @JsonGetter(value = "error")
  public int getError() {
    return error;
  }

  @JsonGetter(value = "data")
  public String getData() {
    return data.toHexString();
  }

  @JsonGetter(value = "stateRootBeforeBlockExecution")
  public String getStateRootBeforeBlockExecution() {
    return stateRootBeforeBlockExecution.toHexString();
  }

  @JsonGetter(value = "stateRootAfterBlockExecution")
  public String getStateRootAfterBlockExecution() {
    return stateRootAfterBlockExecution.toHexString();
  }

  @JsonGetter(value = "stateRootFromWitness")
  public String getStateRootFromWitness() {
    return stateRootFromWitness.toHexString();
  }

  @JsonGetter(value = "stateRootAfterWitnessConsumption")
  public String getStateRootAfterWitnessConsumption() {
    return stateRootAfterWitnessConsumption.toHexString();
  }
}
