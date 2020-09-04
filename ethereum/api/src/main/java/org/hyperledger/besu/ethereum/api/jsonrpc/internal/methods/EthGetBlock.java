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
import org.hyperledger.besu.ethereum.rlp.RLP;

import java.util.Optional;

public class EthGetBlock implements JsonRpcMethod {

  private final BlockchainQueries blockchainQueries;

  public EthGetBlock(final BlockchainQueries blockchainQueries) {
    this.blockchainQueries = blockchainQueries;
  }

  @Override
  public String getName() {
    return RpcMethod.ETH_GET_BLOCK.getMethodName();
  }

  @Override
  public JsonRpcResponse response(final JsonRpcRequestContext requestContext) {
    final long blockNumber = requestContext.getRequiredParameter(0, long.class);

    Blockchain blockchain = blockchainQueries.getBlockchain();
    Optional<Block> maybeBlock = blockchain.getBlockByNumber(blockNumber);

    if (maybeBlock.isEmpty()) {
      return new JsonRpcSuccessResponse(requestContext.getRequest().getId(), null);
    }

    return new JsonRpcSuccessResponse(requestContext.getRequest().getId(), RLP.encode(maybeBlock.get()::writeTo).toBase64String());
  }
}
