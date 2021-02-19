/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.state.appliers;

import io.zeebe.engine.state.TypedEventApplier;
import io.zeebe.engine.state.ZeebeState;
import io.zeebe.engine.state.mutable.MutableElementInstanceState;
import io.zeebe.protocol.impl.record.value.workflowinstance.WorkflowInstanceRecord;
import io.zeebe.protocol.record.intent.WorkflowInstanceIntent;

/** Applies state changes for `WorkflowInstance:Element_Activating` */
final class WorkflowInstanceElementActivatingApplier
    implements TypedEventApplier<WorkflowInstanceIntent, WorkflowInstanceRecord> {

  private final MutableElementInstanceState elementInstanceState;

  public WorkflowInstanceElementActivatingApplier(final ZeebeState state) {
    elementInstanceState = state.getElementInstanceState();
  }

  @Override
  public void applyState(final long elementInstanceKey, final WorkflowInstanceRecord value) {
    final var flowScopeInstance = elementInstanceState.getInstance(value.getFlowScopeKey());
    elementInstanceState.newInstance(
        flowScopeInstance, elementInstanceKey, value, WorkflowInstanceIntent.ELEMENT_ACTIVATING);
  }
}
