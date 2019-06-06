/*
 *
 * Copyright © 2019 Cask Data, Inc.
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

package io.cdap.cdap.graphql.store.application;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import io.cdap.cdap.graphql.CDAPGraphQLTest;
import org.junit.Assert;
import org.junit.Test;

public class ApplicationDataFetcherTest extends CDAPGraphQLTest {

  @Test
  public void testGetApplication() {
    String query = "{ "
      + "  application(name: \"JavascriptTransform\") {"
      + "    name"
      + "    appVersion"
      + "    description"
      + "    configuration"
      + "    ownerPrincipal"
      + "    programs {"
      + "      name"
      + "      type"
      + "      app"
      + "      description"
      + "      ... on Workflow {"
      + "        runs {"
      + "          status"
      + "          startTs"
      + "        }"
      + "        startTimes {"
      + "          time"
      + "        }"
      + "      }"
      + "    }"
      + "  }"
      + "}";

    ExecutionInput executionInput = ExecutionInput.newExecutionInput().query(query).build();
    ExecutionResult executionResult = graphQL.execute(executionInput);
    System.out.println(executionResult.getData().toString());

    Assert.assertTrue(executionResult.getErrors().isEmpty());
  }

}
