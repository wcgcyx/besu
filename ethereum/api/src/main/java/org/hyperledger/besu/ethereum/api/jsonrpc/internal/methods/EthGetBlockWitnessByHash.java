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
import org.hyperledger.besu.ethereum.core.Hash;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.mainnet.BlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.Witness;
import org.hyperledger.besu.ethereum.mainnet.WitnessGenerator;

import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Optional;

public class EthGetBlockWitnessByHash implements JsonRpcMethod {

  private final BlockchainQueries blockchainQueries;
  private final ProtocolSchedule protocolSchedule;

  public EthGetBlockWitnessByHash(final BlockchainQueries blockchainQueries, final ProtocolSchedule protocolSchedule) {
    this.blockchainQueries = blockchainQueries;
    this.protocolSchedule = protocolSchedule;
  }

  @Override
  public String getName() {
    return RpcMethod.ETH_GET_BLOCK_WITNESS_BY_HASH.getMethodName();
  }

  @Override
  public JsonRpcResponse response(final JsonRpcRequestContext requestContext) {
    final Hash blockHash = requestContext.getRequiredParameter(0, Hash.class);

    Blockchain blockchain = blockchainQueries.getBlockchain();
    Optional<Block> maybeBlock = blockchain.getBlockByHash(blockHash);

    if (maybeBlock.isEmpty()) {
      return new JsonRpcSuccessResponse(requestContext.getRequest().getId(), null);
    }

    Block block = maybeBlock.get();
    long blockNumber = block.getHeader().getNumber();
    Optional<MutableWorldState> maybeWorldState = blockchainQueries.getWorldState(blockNumber - 1);

    if (maybeWorldState.isEmpty()) {
      return new JsonRpcSuccessResponse(requestContext.getRequest().getId(), null);
    }

    MutableWorldState worldState = maybeWorldState.get();
    BlockProcessor blockprocessor = protocolSchedule.getByBlockNumber(blockNumber).getBlockProcessor();

    Witness witness = WitnessGenerator.generateWitness(blockprocessor, blockchain, worldState, block);

    return new JsonRpcSuccessResponse(requestContext.getRequest().getId(), witness);
  }
}
