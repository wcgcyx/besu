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
package org.hyperledger.besu.controller;

import static com.google.common.base.Preconditions.checkNotNull;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.config.GenesisConfigFile;
import org.hyperledger.besu.config.GenesisConfigOptions;
import org.hyperledger.besu.config.experimental.ExperimentalEIPs;
import org.hyperledger.besu.crypto.NodeKey;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.methods.JsonRpcMethods;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.blockcreation.MiningCoordinator;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.chain.GenesisState;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.Hash;
import org.hyperledger.besu.ethereum.core.MiningParameters;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.core.PrivacyParameters;
import org.hyperledger.besu.ethereum.core.Synchronizer;
import org.hyperledger.besu.ethereum.core.fees.EIP1559;
import org.hyperledger.besu.ethereum.eth.EthProtocol;
import org.hyperledger.besu.ethereum.eth.EthProtocolConfiguration;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.EthMessages;
import org.hyperledger.besu.ethereum.eth.manager.EthPeers;
import org.hyperledger.besu.ethereum.eth.manager.EthProtocolManager;
import org.hyperledger.besu.ethereum.eth.manager.EthScheduler;
import org.hyperledger.besu.ethereum.eth.peervalidation.ClassicForkPeerValidator;
import org.hyperledger.besu.ethereum.eth.peervalidation.DaoForkPeerValidator;
import org.hyperledger.besu.ethereum.eth.peervalidation.PeerValidator;
import org.hyperledger.besu.ethereum.eth.peervalidation.RequiredBlocksPeerValidator;
import org.hyperledger.besu.ethereum.eth.sync.DefaultSynchronizer;
import org.hyperledger.besu.ethereum.eth.sync.SyncMode;
import org.hyperledger.besu.ethereum.eth.sync.SynchronizerConfiguration;
import org.hyperledger.besu.ethereum.eth.sync.state.SyncState;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPool;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolConfiguration;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolFactory;
import org.hyperledger.besu.ethereum.mainnet.BlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.p2p.config.SubProtocolConfiguration;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.storage.StorageProvider;
import org.hyperledger.besu.ethereum.trie.Node;
import org.hyperledger.besu.ethereum.trie.StoredMerklePatriciaTrie;
import org.hyperledger.besu.ethereum.trie.WitnessTracking;
import org.hyperledger.besu.ethereum.worldstate.MarkSweepPruner;
import org.hyperledger.besu.ethereum.worldstate.Pruner;
import org.hyperledger.besu.ethereum.worldstate.PrunerConfiguration;
import org.hyperledger.besu.ethereum.worldstate.StateTrieAccountValue;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorage;
import org.hyperledger.besu.metrics.ObservableMetricsSystem;

import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public abstract class BesuControllerBuilder {
  private static final Logger LOG = LogManager.getLogger();

  protected GenesisConfigFile genesisConfig;
  private SynchronizerConfiguration syncConfig;
  private EthProtocolConfiguration ethereumWireProtocolConfiguration;
  protected TransactionPoolConfiguration transactionPoolConfiguration;
  protected BigInteger networkId;
  protected MiningParameters miningParameters;
  protected ObservableMetricsSystem metricsSystem;
  protected PrivacyParameters privacyParameters;
  protected Path dataDirectory;
  protected Clock clock;
  protected NodeKey nodeKey;
  protected boolean isRevertReasonEnabled;
  GasLimitCalculator gasLimitCalculator;
  private StorageProvider storageProvider;
  private boolean isPruningEnabled;
  private PrunerConfiguration prunerConfiguration;
  Map<String, String> genesisConfigOverrides;
  private Map<Long, Hash> requiredBlocks = Collections.emptyMap();
  private long reorgLoggingThreshold;

  public BesuControllerBuilder storageProvider(final StorageProvider storageProvider) {
    this.storageProvider = storageProvider;
    return this;
  }

  public BesuControllerBuilder genesisConfigFile(final GenesisConfigFile genesisConfig) {
    this.genesisConfig = genesisConfig;
    return this;
  }

  public BesuControllerBuilder synchronizerConfiguration(
      final SynchronizerConfiguration synchronizerConfig) {
    this.syncConfig = synchronizerConfig;
    return this;
  }

  public BesuControllerBuilder ethProtocolConfiguration(
      final EthProtocolConfiguration ethProtocolConfiguration) {
    this.ethereumWireProtocolConfiguration = ethProtocolConfiguration;
    return this;
  }

  public BesuControllerBuilder networkId(final BigInteger networkId) {
    this.networkId = networkId;
    return this;
  }

  public BesuControllerBuilder miningParameters(final MiningParameters miningParameters) {
    this.miningParameters = miningParameters;
    return this;
  }

  public BesuControllerBuilder nodeKey(final NodeKey nodeKey) {
    this.nodeKey = nodeKey;
    return this;
  }

  public BesuControllerBuilder metricsSystem(final ObservableMetricsSystem metricsSystem) {
    this.metricsSystem = metricsSystem;
    return this;
  }

  public BesuControllerBuilder privacyParameters(final PrivacyParameters privacyParameters) {
    this.privacyParameters = privacyParameters;
    return this;
  }

  public BesuControllerBuilder dataDirectory(final Path dataDirectory) {
    this.dataDirectory = dataDirectory;
    return this;
  }

  public BesuControllerBuilder clock(final Clock clock) {
    this.clock = clock;
    return this;
  }

  public BesuControllerBuilder transactionPoolConfiguration(
      final TransactionPoolConfiguration transactionPoolConfiguration) {
    this.transactionPoolConfiguration = transactionPoolConfiguration;
    return this;
  }

  public BesuControllerBuilder isRevertReasonEnabled(final boolean isRevertReasonEnabled) {
    this.isRevertReasonEnabled = isRevertReasonEnabled;
    return this;
  }

  public BesuControllerBuilder isPruningEnabled(final boolean isPruningEnabled) {
    this.isPruningEnabled = isPruningEnabled;
    return this;
  }

  public BesuControllerBuilder pruningConfiguration(final PrunerConfiguration prunerConfiguration) {
    this.prunerConfiguration = prunerConfiguration;
    return this;
  }

  public BesuControllerBuilder genesisConfigOverrides(
      final Map<String, String> genesisConfigOverrides) {
    this.genesisConfigOverrides = genesisConfigOverrides;
    return this;
  }

  public BesuControllerBuilder targetGasLimit(final Optional<Long> targetGasLimit) {
    this.gasLimitCalculator = new GasLimitCalculator(targetGasLimit);
    return this;
  }

  public BesuControllerBuilder requiredBlocks(final Map<Long, Hash> requiredBlocks) {
    this.requiredBlocks = requiredBlocks;
    return this;
  }

  public BesuControllerBuilder reorgLoggingThreshold(final long reorgLoggingThreshold) {
    this.reorgLoggingThreshold = reorgLoggingThreshold;
    return this;
  }

  public BesuController build() {
    checkNotNull(genesisConfig, "Missing genesis config");
    checkNotNull(syncConfig, "Missing sync config");
    checkNotNull(ethereumWireProtocolConfiguration, "Missing ethereum protocol configuration");
    checkNotNull(networkId, "Missing network ID");
    checkNotNull(miningParameters, "Missing mining parameters");
    checkNotNull(metricsSystem, "Missing metrics system");
    checkNotNull(privacyParameters, "Missing privacy parameters");
    checkNotNull(dataDirectory, "Missing data directory"); // Why do we need this?
    checkNotNull(clock, "Missing clock");
    checkNotNull(transactionPoolConfiguration, "Missing transaction pool configuration");
    checkNotNull(nodeKey, "Missing node key");
    checkNotNull(storageProvider, "Must supply a storage provider");
    checkNotNull(gasLimitCalculator, "Missing gas limit calculator");

    prepForBuild();

    final ProtocolSchedule protocolSchedule = createProtocolSchedule();
    final GenesisState genesisState = GenesisState.fromConfig(genesisConfig, protocolSchedule);
    final ProtocolContext protocolContext =
        ProtocolContext.init(
            storageProvider,
            genesisState,
            protocolSchedule,
            metricsSystem,
            this::createConsensusContext,
            reorgLoggingThreshold);
    validateContext(protocolContext);

    protocolSchedule.setPublicWorldStateArchiveForPrivacyBlockProcessor(
        protocolContext.getWorldStateArchive());

    final MutableBlockchain blockchain = protocolContext.getBlockchain();

    Optional<Pruner> maybePruner = Optional.empty();
    if (isPruningEnabled) {
      if (!storageProvider.isWorldStateIterable()) {
        LOG.warn(
            "Cannot enable pruning with current database version. Disabling. Resync to get the latest database version or disable pruning explicitly on the command line to remove this warning.");
      } else {
        maybePruner =
            Optional.of(
                new Pruner(
                    new MarkSweepPruner(
                        protocolContext.getWorldStateArchive().getWorldStateStorage(),
                        blockchain,
                        storageProvider.createPruningStorage(),
                        metricsSystem),
                    blockchain,
                    prunerConfiguration));
      }
    }
    final EthPeers ethPeers = new EthPeers(getSupportedProtocol(), clock, metricsSystem);
    final EthMessages ethMessages = new EthMessages();
    final EthScheduler scheduler =
        new EthScheduler(
            syncConfig.getDownloaderParallelism(),
            syncConfig.getTransactionsParallelism(),
            syncConfig.getComputationParallelism(),
            metricsSystem);
    final EthContext ethContext = new EthContext(ethPeers, ethMessages, scheduler);
    final SyncState syncState = new SyncState(blockchain, ethPeers);
    final boolean fastSyncEnabled = SyncMode.FAST.equals(syncConfig.getSyncMode());

    final Optional<EIP1559> eip1559;
    final GenesisConfigOptions genesisConfigOptions =
        genesisConfig.getConfigOptions(genesisConfigOverrides);
    if (ExperimentalEIPs.eip1559Enabled
        && genesisConfigOptions.getEIP1559BlockNumber().isPresent()) {
      eip1559 = Optional.of(new EIP1559(genesisConfigOptions.getEIP1559BlockNumber().getAsLong()));
    } else {
      eip1559 = Optional.empty();
    }
    final TransactionPool transactionPool =
        TransactionPoolFactory.createTransactionPool(
            protocolSchedule,
            protocolContext,
            ethContext,
            clock,
            metricsSystem,
            syncState,
            miningParameters.getMinTransactionGasPrice(),
            transactionPoolConfiguration,
            ethereumWireProtocolConfiguration.isEth65Enabled(),
            eip1559);

    final EthProtocolManager ethProtocolManager =
        createEthProtocolManager(
            protocolContext,
            fastSyncEnabled,
            transactionPool,
            ethereumWireProtocolConfiguration,
            ethPeers,
            ethContext,
            ethMessages,
            scheduler,
            createPeerValidators(protocolSchedule));

    final Synchronizer synchronizer =
        new DefaultSynchronizer(
            syncConfig,
            protocolSchedule,
            protocolContext,
            protocolContext.getWorldStateArchive().getWorldStateStorage(),
            ethProtocolManager.getBlockBroadcaster(),
            maybePruner,
            ethProtocolManager.ethContext(),
            syncState,
            dataDirectory,
            clock,
            metricsSystem);

    final MiningCoordinator miningCoordinator =
        createMiningCoordinator(
            protocolSchedule,
            protocolContext,
            transactionPool,
            miningParameters,
            syncState,
            ethProtocolManager);

    final PluginServiceFactory additionalPluginServices =
        createAdditionalPluginServices(blockchain);

    final SubProtocolConfiguration subProtocolConfiguration =
        createSubProtocolConfiguration(ethProtocolManager);

    final JsonRpcMethods additionalJsonRpcMethodFactory =
        createAdditionalJsonRpcMethodFactory(protocolContext);

    final List<Closeable> closeables = new ArrayList<>();
    closeables.add(storageProvider);
    if (privacyParameters.getPrivateStorageProvider() != null) {
      closeables.add(privacyParameters.getPrivateStorageProvider());
    }

    // Generate witness directly
    BlockchainQueries blockchainQueries = new BlockchainQueries(
            protocolContext.getBlockchain(),
            protocolContext.getWorldStateArchive()
    );

    for (int block = 9000000; block <= 10000000; block++) {
      Witness witness = new Witness();
      LOG.info(block);
      generateWitness(
              protocolSchedule.getByBlockNumber(block).getBlockProcessor(),
              blockchain,
              blockchainQueries.getWorldState(block - 1).get(),
              blockchain.getBlockByNumber(block).get(),
              witness);
      try (PrintStream out = new PrintStream(new FileOutputStream("./" + block + ".witness"))) {
        out.println(witness.creationTime.toString());
        out.println(witness.size);
        out.println(witness.stateTrieBranchNodes);
        out.println(witness.stateTrieExtensionNodes);
        out.println(witness.stateTrieHashNodes);
        out.println(witness.stateTrieLeafNodes);
        out.println(witness.stateTrieHashSize);
        out.println(witness.stateTrieLeafSize);
        out.println(witness.stateTrieLeafCode);
        out.println(witness.storageTrieBranchNodes);
        out.println(witness.storageTrieExtensionNodes);
        out.println(witness.storageTrieHashNodes);
        out.println(witness.storageTrieLeafNodes);
        out.println(witness.storageTrieHashSize);
        out.println(witness.storageTrieLeafSize);
      } catch (Exception e) {
        LOG.error(e);
        System.exit(1);
      }
    }
    LOG.info("All Done!");
    System.exit(0);

    return new BesuController(
        protocolSchedule,
        protocolContext,
        ethProtocolManager,
        genesisConfig.getConfigOptions(genesisConfigOverrides),
        subProtocolConfiguration,
        synchronizer,
        syncState,
        transactionPool,
        miningCoordinator,
        privacyParameters,
        miningParameters,
        additionalJsonRpcMethodFactory,
        nodeKey,
        closeables,
        additionalPluginServices);
  }

  private static void generateWitness(final BlockProcessor blockProcessor, final Blockchain blockchain,
                                      final MutableWorldState worldState, final Block block,
                                      final Witness witness) {
    Instant timeStart = Instant.now();

    // Get a copy of the initial world state
    MutableWorldState initialWorldState = worldState.copy();

    // Start tracking
    WitnessTracking.startTracking();

    LOG.info("Start processing block...");

    if (!blockProcessor.processBlock(blockchain, worldState, block).isSuccessful()) {
      LOG.error("Processing block failed.");
      System.exit(1);
    }

    LOG.info("Start generating witness...");

    // Obtain tracking result
    Set<Bytes32> loadedNodes = WitnessTracking.getLoadedNodes();
    Set<Bytes32> loadedCode = WitnessTracking.getLoadedCode();
    Set<Bytes32> loadedStorage = WitnessTracking.getLoadedStorage();

    // Stop tracking
    WitnessTracking.stopTracking();

    // Get storage and root
    WorldStateStorage worldStateStorage = initialWorldState.getWorldStateStorage();
    Node<Bytes> root = initialWorldState.getAccountStateTrie().getRoot();

    generateStateTrieWitness(root, worldStateStorage, loadedNodes, loadedCode, loadedStorage, witness);

    witness.creationTime = Duration.between(timeStart, Instant.now());

    LOG.info("Done.\n");
  }

  private static void generateStateTrieWitness(final Node<Bytes> node, final WorldStateStorage worldStateStorage, final Set<Bytes32> loadedNodes, final Set<Bytes32> loadedCode, final Set<Bytes32> loadedStorage, final Witness witness) {
    if (node.isHashNode()) {
      // First try to load
      if (loadedNodes.contains(node.getHash())) {
        node.load();
        generateStateTrieWitness(node, worldStateStorage, loadedNodes, loadedCode, loadedStorage, witness);
      } else {
        witness.stateTrieHashNodes += 1;
        witness.stateTrieHashSize += 32;
        witness.size += 33;
      }
    } else if (node.isBranchNode()) {
      witness.stateTrieBranchNodes += 1;
      witness.size += 3;
      for (Node<Bytes> child : node.getChildren()) {
        if (child.isNullNode()) continue;
        generateStateTrieWitness(child, worldStateStorage, loadedNodes, loadedCode, loadedStorage, witness);
      }
    } else if (node.isExtensionNode()) {
      witness.stateTrieExtensionNodes += 1;
      witness.size += 2 + node.getExtensionPath().size();
      generateStateTrieWitness(node.getChildren().get(0), worldStateStorage, loadedNodes, loadedCode, loadedStorage, witness);
    } else if (node.isLeafNode()) {
      int startSize = witness.size;
      witness.stateTrieLeafNodes += 1;
      StateTrieAccountValue accountValue = StateTrieAccountValue.readFrom(RLP.input(node.getValue().get()));
      Bytes32 codeHash = accountValue.getCodeHash();
      Bytes32 storageHash = accountValue.getStorageRoot();
      boolean isEOA = codeHash.equals(Hash.EMPTY) && storageHash.equals(Hash.EMPTY_TRIE_HASH);
      witness.size += 98;
      if (!isEOA) {
        Bytes code = worldStateStorage.getCode(codeHash).orElse(Bytes.EMPTY);
        if (loadedCode.contains(codeHash)) {
          witness.size += 5 + code.size();
          witness.stateTrieLeafCode += code.size();
        } else {
          witness.size += 37;
        }
        if (loadedStorage.contains(storageHash)) {
          Node<Bytes> storageRoot = new StoredMerklePatriciaTrie<>(
                  worldStateStorage::getAccountStateTrieNode, storageHash, b -> b, b -> b).getRoot();
          generateStorageTrieWitness(storageRoot, loadedNodes, witness);
        } else {
          witness.size += 33;
        }
      }
      witness.stateTrieLeafSize += witness.size - startSize;
    } else {
      LOG.error("State trie scan failed.");
      System.exit(1);
    }
  }

  private static void generateStorageTrieWitness(final Node<Bytes> node, final Set<Bytes32> loadedNodes, final Witness witness) {
    if (node.isHashNode() || node.isNullNode()) {
      if (node.isHashNode() && loadedNodes.contains(node.getHash())) {
        node.load();
        generateStorageTrieWitness(node, loadedNodes, witness);
      } else {
        witness.storageTrieHashNodes += 1;
        witness.storageTrieHashSize += 32;
        witness.size += 33;
      }
    } else if (node.isBranchNode()) {
      witness.storageTrieBranchNodes += 1;
      witness.size += 3;
      for (Node<Bytes> child : node.getChildren()) {
        if (child.isNullNode()) continue;
        generateStorageTrieWitness(child, loadedNodes, witness);
      }
    } else if (node.isExtensionNode()) {
      witness.storageTrieExtensionNodes += 1;
      witness.size += 2 + node.getExtensionPath().size();
      generateStorageTrieWitness(node.getChildren().get(0), loadedNodes, witness);
    } else if (node.isLeafNode()) {
      witness.storageTrieLeafNodes += 1;
      witness.storageTrieLeafSize += 65;
      witness.size += 65;
    } else {
      LOG.error("Storage trie scan failed.");
      System.exit(1);
    }
  }

  private class Witness {

    public Duration creationTime;
    public int size;
    public int stateTrieBranchNodes;
    public int stateTrieExtensionNodes;
    public int stateTrieHashNodes;
    public int stateTrieLeafNodes;
    public int stateTrieHashSize;
    public int stateTrieLeafSize;
    public int stateTrieLeafCode;
    public int storageTrieBranchNodes;
    public int storageTrieExtensionNodes;
    public int storageTrieHashNodes;
    public int storageTrieLeafNodes;
    public int storageTrieHashSize;
    public int storageTrieLeafSize;

    public Witness() {
      creationTime = null;
      size = 0;
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
      storageTrieHashSize = 0;
      storageTrieLeafSize = 0;
    }
  }

  protected void prepForBuild() {}

  protected JsonRpcMethods createAdditionalJsonRpcMethodFactory(
      final ProtocolContext protocolContext) {
    return apis -> Collections.emptyMap();
  }

  protected SubProtocolConfiguration createSubProtocolConfiguration(
      final EthProtocolManager ethProtocolManager) {
    return new SubProtocolConfiguration().withSubProtocol(EthProtocol.get(), ethProtocolManager);
  }

  protected abstract MiningCoordinator createMiningCoordinator(
      ProtocolSchedule protocolSchedule,
      ProtocolContext protocolContext,
      TransactionPool transactionPool,
      MiningParameters miningParameters,
      SyncState syncState,
      EthProtocolManager ethProtocolManager);

  protected abstract ProtocolSchedule createProtocolSchedule();

  protected void validateContext(final ProtocolContext context) {}

  protected abstract Object createConsensusContext(
      Blockchain blockchain, WorldStateArchive worldStateArchive);

  protected String getSupportedProtocol() {
    return EthProtocol.NAME;
  }

  protected EthProtocolManager createEthProtocolManager(
      final ProtocolContext protocolContext,
      final boolean fastSyncEnabled,
      final TransactionPool transactionPool,
      final EthProtocolConfiguration ethereumWireProtocolConfiguration,
      final EthPeers ethPeers,
      final EthContext ethContext,
      final EthMessages ethMessages,
      final EthScheduler scheduler,
      final List<PeerValidator> peerValidators) {
    return new EthProtocolManager(
        protocolContext.getBlockchain(),
        networkId,
        protocolContext.getWorldStateArchive(),
        transactionPool,
        ethereumWireProtocolConfiguration,
        ethPeers,
        ethMessages,
        ethContext,
        peerValidators,
        fastSyncEnabled,
        scheduler,
        genesisConfig.getForks());
  }

  private List<PeerValidator> createPeerValidators(final ProtocolSchedule protocolSchedule) {
    final List<PeerValidator> validators = new ArrayList<>();

    final OptionalLong daoBlock =
        genesisConfig.getConfigOptions(genesisConfigOverrides).getDaoForkBlock();
    if (daoBlock.isPresent()) {
      // Setup dao validator
      validators.add(
          new DaoForkPeerValidator(protocolSchedule, metricsSystem, daoBlock.getAsLong()));
    }

    final OptionalLong classicBlock =
        genesisConfig.getConfigOptions(genesisConfigOverrides).getClassicForkBlock();
    // setup classic validator
    if (classicBlock.isPresent()) {
      validators.add(
          new ClassicForkPeerValidator(protocolSchedule, metricsSystem, classicBlock.getAsLong()));
    }

    for (final Map.Entry<Long, Hash> requiredBlock : requiredBlocks.entrySet()) {
      validators.add(
          new RequiredBlocksPeerValidator(
              protocolSchedule, metricsSystem, requiredBlock.getKey(), requiredBlock.getValue()));
    }

    return validators;
  }

  protected abstract PluginServiceFactory createAdditionalPluginServices(
      final Blockchain blockchain);
}
