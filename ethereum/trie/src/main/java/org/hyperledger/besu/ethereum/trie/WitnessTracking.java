package org.hyperledger.besu.ethereum.trie;

import org.apache.tuweni.bytes.Bytes32;

import java.util.HashSet;
import java.util.Set;

public class WitnessTracking {

  private static Set<Bytes32> loadedNodes = new HashSet<>();
  private static Set<Bytes32> loadedCode = new HashSet<>();
  private static Set<Bytes32> loadedStorage = new HashSet<>();

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