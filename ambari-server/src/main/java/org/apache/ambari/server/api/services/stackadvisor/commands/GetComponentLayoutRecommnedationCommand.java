/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.server.api.services.stackadvisor.commands;

import java.io.File;

import org.apache.ambari.server.api.services.stackadvisor.StackAdvisorException;
import org.apache.ambari.server.api.services.stackadvisor.StackAdvisorRequest;
import org.apache.ambari.server.api.services.stackadvisor.StackAdvisorRunner;
import org.apache.ambari.server.api.services.stackadvisor.recommendations.RecommendationResponse;

/**
 * {@link StackAdvisorCommand} implementation for component-layout
 * recommendation.
 */
public class GetComponentLayoutRecommnedationCommand extends
    StackAdvisorCommand<RecommendationResponse> {

  public GetComponentLayoutRecommnedationCommand(File recommendationsDir,
      String stackAdvisorScript, int requestId, StackAdvisorRunner saRunner) {
    super(recommendationsDir, stackAdvisorScript, requestId, saRunner);
  }

  @Override
  protected StackAdvisorCommandType getCommandType() {
    return StackAdvisorCommandType.RECOMMEND_COMPONENT_LAYOUT;
  }

  @Override
  protected void validate(StackAdvisorRequest request) throws StackAdvisorException {
    if (request.getHosts().isEmpty() || request.getServices().isEmpty()) {
      throw new StackAdvisorException("Hosts and services must not be empty");
    }
  }

  @Override
  protected StackAdvisorData adjust(StackAdvisorData data, StackAdvisorRequest request) {
    // do nothing
    return data;
  }

  @Override
  protected String getResultFileName() {
    return "component-layout.json";
  }

}
