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

    Blockchain blockchain = blockchainQueries.getBlockchain();
    Optional<Block> maybeBlock = blockchain.getBlockByNumber(blockNumber);
    Optional<MutableWorldState> maybeWorldState = blockchainQueries.getWorldState(blockNumber);

    if (maybeBlock.isEmpty() || maybeWorldState.isEmpty()) {
      return new JsonRpcSuccessResponse(requestContext.getRequest().getId(), null);
    }

    BlockProcessor blockprocessor = protocolSchedule.getByBlockNumber(blockNumber).getBlockProcessor();
    Block block = maybeBlock.get();
    MutableWorldState worldState = maybeWorldState.get();

    Witness witness = WitnessGenerator.generateWitness(blockprocessor, blockchain, worldState, block);

    return new JsonRpcSuccessResponse(requestContext.getRequest().getId(), witness);
  }
}
