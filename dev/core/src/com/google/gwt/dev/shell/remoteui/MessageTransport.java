/*
 * Copyright 2009 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.dev.shell.remoteui;

import com.google.gwt.dev.shell.remoteui.RemoteMessageProto.Message;
import com.google.gwt.dev.shell.remoteui.RemoteMessageProto.Message.Failure;
import com.google.gwt.dev.shell.remoteui.RemoteMessageProto.Message.MessageType;
import com.google.gwt.dev.shell.remoteui.RemoteMessageProto.Message.Request;
import com.google.gwt.dev.shell.remoteui.RemoteMessageProto.Message.Response;
import com.google.gwt.dev.util.Callback;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Responsible for exchanging requests and responses between services.
 */
public class MessageTransport {

  /**
   * An exception that is generated by the transport when it receives a failure
   * message from the server in response to executing a client request.
   */
  @SuppressWarnings("serial")
  public class RequestException extends Exception {
    private final Failure failureMessage;

    /**
     * Create a new instance with the given failure message. The exception's
     * message will be set to {@link Failure#getMessage()}.
     * 
     * @param failureMessage The failure message returned by the server
     */
    public RequestException(Failure failureMessage) {
      super(failureMessage.getMessage());
      this.failureMessage = failureMessage;
    }

    /**
     * Gets the failure message returned by the server.
     */
    public Failure getFailureMessage() {
      return failureMessage;
    }
  }

  /**
   * A callback that is invoked when the transport terminates.
   */
  public interface TerminationCallback {

    /**
     * Called when the transport terminates.
     * 
     * @param e The exception that led to the termination
     */
    void onTermination(Exception e);
  }

  class PendingRequest extends PendingSend {
    private final Callback<Response> callback;
    private final Message message;

    PendingRequest(Message message, Callback<Response> callback) {
      this.message = message;
      this.callback = callback;
    }

    @Override
    void failed(Exception e) {
      assert e != null;
      pendingRequestMap.remove(message.getMessageId());
      callback.onError(e);
    }

    @Override
    void send(OutputStream outputStream) throws IOException {
      int messageId = message.getMessageId();
      pendingRequestMap.put(messageId, this);
      message.writeDelimitedTo(outputStream);
    }

    /**
     * Sets the response that was received from the server, and signals the
     * thread that is waiting on the response.
     * 
     * @param responseMessage the server's response
     * @throws InterruptedException
     */
    void setResponse(Response responseMessage) {
      assert (responseMessage != null);
      callback.onDone(responseMessage);
    }
  }

  static class PendingRequestMap {
    private final Lock mapLock = new ReentrantLock();
    private boolean noMoreAdds;
    private final Map<Integer, PendingRequest> requestIdToPendingServerRequest = new HashMap<Integer, PendingRequest>();

    public void blockAdds(Exception e) {
      mapLock.lock();
      try {
        noMoreAdds = true;
        for (PendingRequest pendingRequest : requestIdToPendingServerRequest.values()) {
          pendingRequest.failed(e);
        }
      } finally {
        mapLock.unlock();
      }
    }

    public PendingRequest remove(int requestId) {
      mapLock.lock();
      try {
        return requestIdToPendingServerRequest.remove(requestId);
      } finally {
        mapLock.unlock();
      }
    }

    void put(int requestId, PendingRequest pendingServerRequest) {
      mapLock.lock();
      try {
        if (noMoreAdds) {
          pendingServerRequest.failed(new IllegalStateException(
              "InputStream is closed"));
        } else {
          requestIdToPendingServerRequest.put(requestId, pendingServerRequest);
        }
      } finally {
        mapLock.unlock();
      }
    }
  }

  class PendingResponse extends PendingSend {
    Message message;

    public PendingResponse(Message message) {
      this.message = message;
    }

    @Override
    public void failed(Exception e) {
      // Do nothing
    }

    @Override
    public void send(OutputStream outputStream) throws IOException {
      message.writeDelimitedTo(outputStream);
    }
  }

  abstract class PendingSend {
    abstract void failed(Exception e);

    abstract void send(OutputStream outputStream) throws IOException;
  }

  /**
   * A callable that does nothing.
   */
  private static final Callable<Response> DUMMY_CALLABLE = new Callable<Response>() {
    public Response call() throws Exception {
      return null;
    }
  };
  private final InputStream inputStream;
  private final AtomicBoolean isStarted = new AtomicBoolean(false);
  private final AtomicInteger nextMessageId = new AtomicInteger();
  private final OutputStream outputStream;
  private final PendingRequestMap pendingRequestMap = new PendingRequestMap();
  private final RequestProcessor requestProcessor;
  private final LinkedBlockingQueue<PendingSend> sendQueue = new LinkedBlockingQueue<PendingSend>();

  private final TerminationCallback terminationCallback;

  /**
   * Create a new instance using the given streams and request processor.
   * 
   * @param inputStream an input stream for reading messages
   * @param outputStream an output stream for writing messages
   * @param requestProcessor a callback interface for handling remote client
   *          requests
   * @param terminationCallback a callback that is invoked when the transport
   *          terminates
   */
  public MessageTransport(final InputStream inputStream,
      final OutputStream outputStream, RequestProcessor requestProcessor,
      TerminationCallback terminationCallback) {
    this.requestProcessor = requestProcessor;
    this.terminationCallback = terminationCallback;
    this.inputStream = inputStream;
    this.outputStream = outputStream;
  }

  /**
   * Asynchronously executes the request on a remote server.
   * 
   * @param requestMessage The request to execute
   * 
   * @return a {@link Future} that can be used to access the server's response
   */
  public Future<Response> executeRequestAsync(Request requestMessage) {
    Message.Builder messageBuilder = Message.newBuilder();
    int messageId = nextMessageId.getAndIncrement();
    messageBuilder.setMessageId(messageId);
    messageBuilder.setMessageType(Message.MessageType.REQUEST);
    messageBuilder.setRequest(requestMessage);

    Message message = messageBuilder.build();

    class FutureTaskExtension extends FutureTask<Response> {
      private FutureTaskExtension() {
        super(DUMMY_CALLABLE);
      }

      @Override
      public void set(Response v) {
        super.set(v);
      }

      @Override
      public void setException(Throwable t) {
        super.setException(t);
      }
    }

    final FutureTaskExtension future = new FutureTaskExtension();
    PendingRequest pendingRequest = new PendingRequest(message,
        new Callback<Response>() {

          public void onDone(Response result) {
            future.set(result);
          }

          public void onError(Throwable t) {
            future.setException(t);
          }
        });
    sendQueue.add(pendingRequest);
    return future;
  }

  /**
   * Asynchronously executes the request on a remote server. The callback will
   * generally be called by another thread. Memory consistency effects: actions
   * in a thread prior to calling this method happen-before the callback is
   * invoked.
   * 
   * @param requestMessage The request to execute
   * @param callback The callback to invoke when the response is received
   */
  public void executeRequestAsync(Request requestMessage,
      Callback<Response> callback) {
    Message.Builder messageBuilder = Message.newBuilder();
    int messageId = nextMessageId.getAndIncrement();
    messageBuilder.setMessageId(messageId);
    messageBuilder.setMessageType(Message.MessageType.REQUEST);
    messageBuilder.setRequest(requestMessage);

    Message message = messageBuilder.build();
    PendingRequest pendingRequest = new PendingRequest(message, callback);
    sendQueue.add(pendingRequest);
  }

  /**
   * Starts up the message transport. The message transport creates its own
   * threads, so it is not necessary to invoke this method from a separate
   * thread.
   * 
   * Closing either stream will cause the termination of the transport.
   */
  public void start() {

    if (isStarted.getAndSet(true)) {
      return;
    }

    // This thread terminates on interruption or IO failure
    Thread messageProcessingThread = new Thread(new Runnable() {
      public void run() {
        try {
          while (true) {
            Message message = Message.parseDelimitedFrom(inputStream);
            // TODO: This is where we would do a protocol check
            processMessage(message);
          }
        } catch (IOException e) {
          terminateDueToException(e);
        } catch (InterruptedException e) {
          terminateDueToException(e);
        }
      }
    });
    messageProcessingThread.start();

    // This thread only terminates if it is interrupted
    Thread sendThread = new Thread(new Runnable() {
      public void run() {
        while (true) {
          try {
            PendingSend pendingSend = sendQueue.take();
            try {
              pendingSend.send(outputStream);
            } catch (IOException e) {
              pendingSend.failed(e);
            }
          } catch (InterruptedException e) {
            break;
          }
        }
      }
    });
    sendThread.setDaemon(true);
    sendThread.start();
  }

  private void processClientRequest(int messageId, Request request)
      throws InterruptedException {
    Message.Builder messageBuilder = Message.newBuilder();
    messageBuilder.setMessageId(messageId);

    Response response = null;
    try {
      messageBuilder.setMessageType(Message.MessageType.RESPONSE);
      response = requestProcessor.execute(request);
      messageBuilder.setResponse(response);
    } catch (Exception e) {
      messageBuilder.setMessageType(Message.MessageType.FAILURE);
      Message.Failure.Builder failureMessage = Message.Failure.newBuilder();

      failureMessage.setMessage(e.getLocalizedMessage() != null
          ? e.getLocalizedMessage() : e.getClass().getName());
      StringWriter sw = new StringWriter();
      e.printStackTrace(new PrintWriter(sw, true));
      failureMessage.setStackTrace(sw.getBuffer().toString());
      messageBuilder.setFailure(failureMessage);
    }

    PendingResponse pendingResponse = new PendingResponse(
        messageBuilder.build());
    sendQueue.put(pendingResponse);
  }

  private void processFailure(int messageId, Failure failure) {
    PendingRequest pendingServerRequest = pendingRequestMap.remove(messageId);
    if (pendingServerRequest != null) {
      pendingServerRequest.failed(new RequestException(failure));
    }
  }

  private void processMessage(final Message message)
      throws InterruptedException {

    MessageType messageType = message.getMessageType();
    if (messageType == null) {
      processUnknownMessageType(message.getMessageId(), "unknown");
      return;
    }

    switch (messageType) {
      case RESPONSE: {
        processServerResponse(message.getMessageId(), message.getResponse());
        break;
      }

      case REQUEST: {
        processClientRequest(message.getMessageId(), message.getRequest());
        break;
      }

      case FAILURE: {
        processFailure(message.getMessageId(), message.getFailure());
        break;
      }

      default: {
        processUnknownMessageType(message.getMessageId(),
            messageType.name());
        break;
      }
    }
  }

  private void processServerResponse(int messageId, Response response) {
    PendingRequest pendingServerRequest = pendingRequestMap.remove(messageId);
    if (pendingServerRequest != null) {
      pendingServerRequest.setResponse(response);
    }
  }

  private void processUnknownMessageType(int messageId, String messageTypeName)
      throws InterruptedException {
    Message.Builder messageBuilder = Message.newBuilder();
    messageBuilder.setMessageId(messageId);
    messageBuilder.setMessageType(Message.MessageType.FAILURE);
    Message.Failure.Builder failureMessage = Message.Failure.newBuilder();

    StringBuffer stringBuffer = new StringBuffer();
    stringBuffer.append("Unknown message type '");
    stringBuffer.append(messageTypeName);
    stringBuffer.append("'. Known message types are: ");

    for (MessageType type : MessageType.values()) {
      stringBuffer.append(type.name());
      stringBuffer.append(" ");
    }

    failureMessage.setMessage(stringBuffer.toString());
    messageBuilder.setFailure(failureMessage);

    PendingResponse pendingResponse = new PendingResponse(
        messageBuilder.build());
    sendQueue.put(pendingResponse);
  }

  private void terminateDueToException(Exception e) {
    pendingRequestMap.blockAdds(e);
    if (terminationCallback != null) {
      terminationCallback.onTermination(e);
    }
  }
}
