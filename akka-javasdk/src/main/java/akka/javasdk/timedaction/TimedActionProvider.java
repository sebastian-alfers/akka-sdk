/*
 * Copyright (C) 2021-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.javasdk.timedaction;

import com.google.protobuf.Descriptors;
import akka.javasdk.Kalix;
import akka.javasdk.impl.MessageCodec;
import akka.javasdk.impl.timedaction.TimedActionRouter;

import java.util.Optional;

/**
 * Register a TimedAction in {{@link Kalix}} using an <code> TimedActionProvider</code>.
 */
public interface TimedActionProvider<A extends TimedAction> {

  TimedActionOptions options();

  Descriptors.ServiceDescriptor serviceDescriptor();

  TimedActionRouter<A> newRouter(TimedActionContext context);

  Descriptors.FileDescriptor[] additionalDescriptors();

  default Optional<MessageCodec> alternativeCodec() {
    return Optional.empty();
  }
}