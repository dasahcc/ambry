package com.github.ambry.network;

import com.github.ambry.utils.ByteBufferOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;


/**
 * A byte buffer version of Send to buffer the outgoing responses before sending.
 * This is mainly used to optimize serialization of response objects on the request handler
 * threads rather than the network threads.
 */
public class BoundedByteBufferSend implements WritableByteChannel {

  private ByteBuffer buffer;

  public BoundedByteBufferSend(){

  }

  public BoundedByteBufferSend(Send request)
      throws IOException {
    // Avoid possibility of overflow for 2GB-4 byte buffer
    if (request.sizeInBytes() > Integer.MAX_VALUE - 4) {
      throw new IllegalStateException(
          "Maximum allowable size for a bounded buffer is " + (Integer.MAX_VALUE - 4) + ".");
    }

    int size = (int) request.sizeInBytes();
    buffer = ByteBuffer.allocate(size + 4);
    buffer.putInt(size);
    WritableByteChannel channel = Channels.newChannel(new ByteBufferOutputStream(buffer));
    request.writeTo(channel);
    buffer.rewind();
  }

  public BoundedByteBufferSend(ByteBuffer buffer) {
    if (buffer == null) {
      throw new IllegalArgumentException("Input buffer cannot be null for BoundedByteBufferSend");
    }
    this.buffer = buffer;
  }

  public void writeTo(WritableByteChannel channel)
      throws IOException {
    if (!isSendComplete()) {
      channel.write(buffer);
    }
  }

  public boolean isSendComplete() {
    return buffer.remaining() == 0;
  }

  public long sizeInBytes() {
    return buffer.limit();
  }

  public BoundedByteBufferSend duplicate() {
    return new BoundedByteBufferSend(buffer.duplicate());
  }

  @Override
  public int write(ByteBuffer src)
      throws IOException {
    this.buffer.clear();
    this.buffer = ByteBuffer.wrap(src.array());
    return 0;  //To change body of implemented methods use File | Settings | File Templates.
  }

  @Override
  public boolean isOpen() {
    return true;
  }

  @Override
  public void close()
      throws IOException {
    this.buffer.clear();
  }
}