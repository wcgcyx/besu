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
