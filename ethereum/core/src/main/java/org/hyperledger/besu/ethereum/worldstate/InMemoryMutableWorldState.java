package org.hyperledger.besu.ethereum.worldstate;

import com.sun.source.doctree.UnknownInlineTagTree;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.ethereum.core.AbstractWorldUpdater;
import org.hyperledger.besu.ethereum.core.Account;
import org.hyperledger.besu.ethereum.core.AccountStorageEntry;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.Hash;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.core.Wei;
import org.hyperledger.besu.ethereum.core.WorldUpdater;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.rlp.RLPException;
import org.hyperledger.besu.ethereum.rlp.RLPInput;
import org.hyperledger.besu.ethereum.trie.MerklePatriciaTrie;
import org.hyperledger.besu.ethereum.trie.SimpleMerklePatriciaTrie;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public class InMemoryMutableWorldState implements MutableWorldState {

  private final MerklePatriciaTrie<Bytes32, Bytes> accountStateTrie;
  private final Map<Bytes32, Bytes> accessedCode;
  private final Map<Bytes32, MerklePatriciaTrie<Bytes32, Bytes>> accessedStorage;

  private final Map<Address, MerklePatriciaTrie<Bytes32, Bytes>> updatedStorageTries =
          new HashMap<>();
  private final Map<Address, Bytes> updatedAccountCode = new HashMap<>();
//  private final Map<Bytes32, UInt256> newStorageKeyPreimages = new HashMap<>();
//  private final Map<Bytes32, Address> newAccountKeyPreimages = new HashMap<>();

  public InMemoryMutableWorldState(
      final MerklePatriciaTrie<Bytes32, Bytes> accountStateTrie,
      final Map<Bytes32, Bytes> accessedCode,
      final Map<Bytes32, MerklePatriciaTrie<Bytes32, Bytes>> accessedStorage) {
    this.accountStateTrie = accountStateTrie;
    this.accessedCode = accessedCode;
    this.accessedStorage = accessedStorage;
  }

  @Override
  public Hash rootHash() {
    return Hash.wrap(accountStateTrie.getRootHash());
  }

  @Override
  public MutableWorldState copy() {
    //TODO: Check if this would be used.
    return null;
  }

  @Override
  public Account get(final Address address) {
    final Hash addressHash = Hash.hash(address);
    return accountStateTrie
            .get(addressHash)
            .map(bytes -> deserializeAccount(address, addressHash, bytes))
            .orElse(null);
  }

  private WorldStateAccount deserializeAccount(
          final Address address, final Hash addressHash, final Bytes encoded) throws RLPException {
    final RLPInput in = RLP.input(encoded);
    final StateTrieAccountValue accountValue = StateTrieAccountValue.readFrom(in);
    return new WorldStateAccount(address, addressHash, accountValue);
  }

  private static Bytes serializeAccount(
          final long nonce,
          final Wei balance,
          final Hash storageRoot,
          final Hash codeHash,
          final int version) {
    final StateTrieAccountValue accountValue =
            new StateTrieAccountValue(nonce, balance, storageRoot, codeHash, version);
    return RLP.encode(accountValue::writeTo);
  }

  @Override
  public WorldUpdater updater() {
    return new Updater(this);
  }

  @Override
  public Stream<StreamableAccount> streamAccounts(final Bytes32 startKeyHash, final int limit) {
    //TODO: Check if this would be used.
    return null;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(rootHash());
  }

  @Override
  public final boolean equals(final Object other) {
    if (!(other instanceof InMemoryMutableWorldState)) {
      return false;
    }

    final InMemoryMutableWorldState that = (InMemoryMutableWorldState) other;
    return this.rootHash().equals(that.rootHash());
  }

  @Override
  public void persist() {
  }

  protected class WorldStateAccount implements Account {

    private final Address address;
    private final Hash addressHash;
    private final StateTrieAccountValue accountValue;
    private volatile MerklePatriciaTrie<Bytes32, Bytes> storageTrie;

    private WorldStateAccount(
        final Address address, final Hash addressHash, final StateTrieAccountValue accountValue) {
      this.address = address;
      this.addressHash = addressHash;
      this.accountValue = accountValue;
    }

    private MerklePatriciaTrie<Bytes32, Bytes> storageTrie() {
      final MerklePatriciaTrie<Bytes32, Bytes> updatedTrie = updatedStorageTries.get(address);
      if (updatedTrie != null) {
        storageTrie = updatedTrie;
      }
      if (storageTrie == null) {
        storageTrie = accessedStorage.getOrDefault(getStorageRoot(), null).copy();
      }
      return storageTrie;
    }

    @Override
    public Address getAddress() {
      return address;
    }

    @Override
    public Hash getAddressHash() {
      return addressHash;
    }

    @Override
    public long getNonce() {
      return accountValue.getNonce();
    }

    @Override
    public Wei getBalance() {
      return accountValue.getBalance();
    }

    private Hash getStorageRoot() {
      return accountValue.getStorageRoot();
    }

    @Override
    public Bytes getCode() {
      final Bytes updatedCode = updatedAccountCode.get(address);
      if (updatedCode != null) {
        return updatedCode;
      }
      final Hash codeHash = getCodeHash();
      if (codeHash.equals(Hash.EMPTY)) {
        return Bytes.EMPTY;
      }
      return accessedCode.getOrDefault(codeHash, Bytes.EMPTY);
    }

    @Override
    public boolean hasCode() {
      return !getCode().isEmpty();
    }

    @Override
    public Hash getCodeHash() {
      return accountValue.getCodeHash();
    }

    @Override
    public int getVersion() {
      return accountValue.getVersion();
    }

    @Override
    public UInt256 getStorageValue(final UInt256 key) {
      final Optional<Bytes> val = storageTrie().get(Hash.hash(key.toBytes()));
      if (val.isEmpty()) {
        return UInt256.ZERO;
      }
      return convertToUInt256(val.get());
    }

    @Override
    public UInt256 getOriginalStorageValue(final UInt256 key) {
      return getStorageValue(key);
    }

    @Override
    public NavigableMap<Bytes32, AccountStorageEntry> storageEntriesFrom(
            final Bytes32 startKeyHash, final int limit) {
      //TODO: Check if this would be used.
      return null;
    }

    private UInt256 convertToUInt256(final Bytes value) {
      final RLPInput in = RLP.input(value);
      return in.readUInt256Scalar();
    }


    @Override
    public String toString() {
      final StringBuilder builder = new StringBuilder();
      builder.append("AccountState").append("{");
      builder.append("address=").append(getAddress()).append(", ");
      builder.append("nonce=").append(getNonce()).append(", ");
      builder.append("balance=").append(getBalance()).append(", ");
      builder.append("storageRoot=").append(getStorageRoot()).append(", ");
      builder.append("codeHash=").append(getCodeHash());
      builder.append("version=").append(getVersion());
      return builder.append("}").toString();
    }
  }

  protected static class Updater
      extends AbstractWorldUpdater<InMemoryMutableWorldState, WorldStateAccount> {

    protected Updater(final InMemoryMutableWorldState world) { super(world); }

    @Override
    protected WorldStateAccount getForMutation(final Address address) {
      final InMemoryMutableWorldState wrapped = wrappedWorldView();
      final Hash addressHash = Hash.hash(address);
      return wrapped
          .accountStateTrie
          .get(addressHash)
          .map(bytes -> wrapped.deserializeAccount(address, addressHash, bytes))
          .orElse(null);
    }

    @Override
    public Collection<UpdateTrackingAccount<? extends Account>> getTouchedAccounts() {
      return new ArrayList<>(updatedAccounts());
    }

    @Override
    public Collection<Address> getDeletedAccountAddresses() {
      return new ArrayList<>(deletedAccounts());
    }

    @Override
    public void revert() {
      deletedAccounts().clear();
      updatedAccounts().clear();
    }

    @Override
    public void commit() {
      final InMemoryMutableWorldState wrapped = wrappedWorldView();

      for (final Address address : deletedAccounts()) {
        final Hash addressHash = Hash.hash(address);
        wrapped.accountStateTrie.remove(addressHash);
        wrapped.updatedStorageTries.remove(address);
        wrapped.updatedAccountCode.remove(address);
      }

      for (final UpdateTrackingAccount<WorldStateAccount> updated : updatedAccounts()) {
        final WorldStateAccount origin = updated.getWrappedAccount();

        Hash codeHash = origin == null ? Hash.EMPTY : origin.getCodeHash();
        if (updated.codeWasUpdated()) {
          codeHash = Hash.hash(updated.getCode());
          wrapped.updatedAccountCode.put(updated.getAddress(), updated.getCode());
        }
        final boolean freshState = origin == null || updated.getStorageWasCleared();
        Hash storageRoot = freshState ? Hash.EMPTY_TRIE_HASH : origin.getStorageRoot();
        if (freshState) {
          wrapped.updatedStorageTries.remove(updated.getAddress());
        }
        final Map<UInt256, UInt256> updatedStorage = updated.getUpdatedStorage();
        if (!updatedStorage.isEmpty()) {
          final MerklePatriciaTrie<Bytes32, Bytes> storageTrie =
                  freshState ?
                          new SimpleMerklePatriciaTrie<>(b -> b) :
                          origin.storageTrie();
          wrapped.updatedStorageTries.put(updated.getAddress(), storageTrie);
          for (final Map.Entry<UInt256, UInt256> entry : updatedStorage.entrySet()) {
            final UInt256 value = entry.getValue();
            final Hash keyHash = Hash.hash(entry.getKey().toBytes());
            if (value.isZero()) {
              storageTrie.remove(keyHash);
            } else {
//              wrapped.newStorageKeyPreimages.put(keyHash, entry.getKey());
              storageTrie.put(keyHash, RLP.encode(out -> out.writeBytes(entry.getValue().toMinimalBytes())));
            }
          }
          storageRoot = Hash.wrap(storageTrie.getRootHash());
        }
//        wrapped.newAccountKeyPreimages.put(updated.getAddressHash(), updated.getAddress());
        final Bytes account = serializeAccount(
                updated.getNonce(),
                updated.getBalance(),
                storageRoot,
                codeHash,
                updated.getVersion());
        wrapped.accountStateTrie.put(updated.getAddressHash(), account);
      }
    }
  }
}
