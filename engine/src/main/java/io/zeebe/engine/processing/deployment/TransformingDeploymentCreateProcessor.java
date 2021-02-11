/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.processing.deployment;

import static io.zeebe.engine.state.instance.TimerInstance.NO_ELEMENT_INSTANCE;

import io.zeebe.engine.processing.common.CatchEventBehavior;
import io.zeebe.engine.processing.common.ExpressionProcessor;
import io.zeebe.engine.processing.common.ExpressionProcessor.EvaluationException;
import io.zeebe.engine.processing.common.Failure;
import io.zeebe.engine.processing.deployment.model.element.ExecutableCatchEventElement;
import io.zeebe.engine.processing.deployment.model.element.ExecutableStartEvent;
import io.zeebe.engine.processing.deployment.transform.DeploymentTransformer;
import io.zeebe.engine.processing.streamprocessor.TypedRecord;
import io.zeebe.engine.processing.streamprocessor.TypedRecordProcessor;
import io.zeebe.engine.processing.streamprocessor.sideeffect.SideEffectProducer;
import io.zeebe.engine.processing.streamprocessor.writers.TypedResponseWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedStreamWriter;
import io.zeebe.engine.state.KeyGenerator;
import io.zeebe.engine.state.ZeebeState;
import io.zeebe.engine.state.immutable.TimerInstanceState;
import io.zeebe.engine.state.instance.TimerInstance;
import io.zeebe.engine.state.mutable.MutableEventScopeInstanceState;
import io.zeebe.engine.state.mutable.MutableProcessState;
import io.zeebe.model.bpmn.util.time.Timer;
import io.zeebe.protocol.impl.record.value.deployment.DeploymentRecord;
import io.zeebe.protocol.impl.record.value.deployment.Process;
import io.zeebe.protocol.record.RejectionType;
import io.zeebe.protocol.record.intent.DeploymentIntent;
import io.zeebe.util.Either;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.agrona.DirectBuffer;

public final class TransformingDeploymentCreateProcessor
    implements TypedRecordProcessor<DeploymentRecord> {

  private static final String COULD_NOT_CREATE_TIMER_MESSAGE =
      "Expected to create timer for start event, but encountered the following error: %s";
  private final DeploymentTransformer deploymentTransformer;
  private final MutableProcessState processState;
  private final MutableEventScopeInstanceState eventScopeInstanceState;
  private final TimerInstanceState timerInstanceState;
  private final CatchEventBehavior catchEventBehavior;
  private final KeyGenerator keyGenerator;
  private final ExpressionProcessor expressionProcessor;

  public TransformingDeploymentCreateProcessor(
      final ZeebeState zeebeState,
      final CatchEventBehavior catchEventBehavior,
      final ExpressionProcessor expressionProcessor) {
    processState = zeebeState.getProcessState();
    eventScopeInstanceState = zeebeState.getEventScopeInstanceState();
    timerInstanceState = zeebeState.getTimerState();
    keyGenerator = zeebeState.getKeyGenerator();
    deploymentTransformer = new DeploymentTransformer(zeebeState, expressionProcessor);
    this.catchEventBehavior = catchEventBehavior;
    this.expressionProcessor = expressionProcessor;
  }

  @Override
  public void processRecord(
      final TypedRecord<DeploymentRecord> command,
      final TypedResponseWriter responseWriter,
      final TypedStreamWriter streamWriter,
      final Consumer<SideEffectProducer> sideEffect) {
    final DeploymentRecord deploymentEvent = command.getValue();

    final boolean accepted = deploymentTransformer.transform(deploymentEvent);
    if (accepted) {
      final long key = keyGenerator.nextKey();
      processState.putDeployment(deploymentEvent);

      try {
        createTimerIfTimerStartEvent(command, streamWriter);
      } catch (final RuntimeException e) {
        final String reason = String.format(COULD_NOT_CREATE_TIMER_MESSAGE, e.getMessage());
        responseWriter.writeRejectionOnCommand(command, RejectionType.PROCESSING_ERROR, reason);
        streamWriter.appendRejection(command, RejectionType.PROCESSING_ERROR, reason);
        return;
      }

      responseWriter.writeEventOnCommand(key, DeploymentIntent.CREATED, deploymentEvent, command);
      streamWriter.appendFollowUpEvent(key, DeploymentIntent.CREATED, deploymentEvent);
    } else {
      responseWriter.writeRejectionOnCommand(
          command,
          deploymentTransformer.getRejectionType(),
          deploymentTransformer.getRejectionReason());
      streamWriter.appendRejection(
          command,
          deploymentTransformer.getRejectionType(),
          deploymentTransformer.getRejectionReason());
    }
  }

  private void createTimerIfTimerStartEvent(
      final TypedRecord<DeploymentRecord> record, final TypedStreamWriter streamWriter) {
    for (final Process process : record.getValue().processs()) {
      final List<ExecutableStartEvent> startEvents =
          processState.getProcessByKey(process.getKey()).getProcess().getStartEvents();
      boolean hasAtLeastOneTimer = false;

      unsubscribeFromPreviousTimers(streamWriter, process);

      for (final ExecutableCatchEventElement startEvent : startEvents) {
        if (startEvent.isTimer()) {
          hasAtLeastOneTimer = true;

          // There are no variables when there is no process instance yet,
          // we use a negative scope key to indicate this
          final long scopeKey = -1L;
          final Either<Failure, Timer> timerOrError =
              startEvent.getTimerFactory().apply(expressionProcessor, scopeKey);
          if (timerOrError.isLeft()) {
            // todo(#4323): deal with this exceptional case without throwing an exception
            throw new EvaluationException(timerOrError.getLeft().getMessage());
          }
          catchEventBehavior.subscribeToTimerEvent(
              NO_ELEMENT_INSTANCE,
              NO_ELEMENT_INSTANCE,
              process.getKey(),
              startEvent.getId(),
              timerOrError.get(),
              streamWriter);
        }
      }

      if (hasAtLeastOneTimer) {
        eventScopeInstanceState.createIfNotExists(process.getKey(), Collections.emptyList());
      }
    }
  }

  private void unsubscribeFromPreviousTimers(
      final TypedStreamWriter streamWriter, final Process process) {
    timerInstanceState.forEachTimerForElementInstance(
        NO_ELEMENT_INSTANCE, timer -> unsubscribeFromPreviousTimer(streamWriter, process, timer));
  }

  private void unsubscribeFromPreviousTimer(
      final TypedStreamWriter streamWriter, final Process process, final TimerInstance timer) {
    final DirectBuffer timerBpmnId =
        processState.getProcessByKey(timer.getProcessKey()).getBpmnProcessId();

    if (timerBpmnId.equals(process.getBpmnProcessIdBuffer())) {
      catchEventBehavior.unsubscribeFromTimerEvent(timer, streamWriter);
    }
  }
}
