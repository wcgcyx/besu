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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods;

import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.mainnet.BlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.Witness;
import org.hyperledger.besu.ethereum.mainnet.WitnessGenerator;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Optional;

public class EthGetBlockWitnessByNumber implements JsonRpcMethod {

  private final BlockchainQueries blockchainQueries;
  private final ProtocolSchedule protocolSchedule;

  public EthGetBlockWitnessByNumber(final BlockchainQueries blockchainQueries, final ProtocolSchedule protocolSchedule) {
    this.blockchainQueries = blockchainQueries;
    this.protocolSchedule = protocolSchedule;
  }

  @Override
  public String getName() {
    return RpcMethod.ETH_GET_BLOCK_WITNESS_BY_NUMBER.getMethodName();
  }

  @Override
  public JsonRpcResponse response(final JsonRpcRequestContext requestContext) {
    final long blockNumber = requestContext.getRequiredParameter(0, long.class);
    final Optional<String> fileName = requestContext.getOptionalParameter(1, String.class);

    Blockchain blockchain = blockchainQueries.getBlockchain();
    Optional<Block> maybeBlock = blockchain.getBlockByNumber(blockNumber);
    Optional<MutableWorldState> maybeWorldState = blockchainQueries.getWorldState(blockNumber - 1);

    if (maybeBlock.isEmpty() || maybeWorldState.isEmpty()) {
      return new JsonRpcSuccessResponse(requestContext.getRequest().getId(), null);
    }

    BlockProcessor blockprocessor = protocolSchedule.getByBlockNumber(blockNumber).getBlockProcessor();
    Block block = maybeBlock.get();
    MutableWorldState worldState = maybeWorldState.get();

    WitnessGenerator.generateWitness(blockprocessor, blockchain, worldState, block);

    if (fileName.isPresent()) {
      try (PrintStream out = new PrintStream(new FileOutputStream(fileName.get()))) {
        out.println(Witness.error);
        out.println(Witness.errorMsg);
        out.println(Witness.data.size());
        out.println(Witness.creationTime.toString());
        out.println(Witness.stateTrieBranchNodes);
        out.println(Witness.stateTrieExtensionNodes);
        out.println(Witness.stateTrieHashNodes);
        out.println(Witness.stateTrieLeafNodes);
        out.println(Witness.stateTrieHashSize);
        out.println(Witness.stateTrieLeafSize);
        out.println(Witness.stateTrieLeafCode);
        out.println(Witness.storageTrieBranchNodes);
        out.println(Witness.storageTrieExtensionNodes);
        out.println(Witness.storageTrieHashNodes);
        out.println(Witness.storageTrieLeafNodes);
        out.println(Witness.storageTrieHashsize);
        out.println(Witness.storageTrieLeafSize);
        Witness.clear();
      } catch (Exception e) {
        return new JsonRpcSuccessResponse(requestContext.getRequest().getId(), null);
      }
    }

    return new JsonRpcSuccessResponse(requestContext.getRequest().getId(), Witness.error);
  }
}
