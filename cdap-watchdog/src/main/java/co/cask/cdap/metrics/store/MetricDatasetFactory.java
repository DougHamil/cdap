/*
 * Copyright 2015-2017 Cask Data, Inc.
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

package co.cask.cdap.metrics.store;

import co.cask.cdap.data2.dataset2.lib.timeseries.FactTable;
import co.cask.cdap.metrics.process.AbstractConsumerMetaTable;
import co.cask.cdap.metrics.process.AbstractMetricsProcessorService;
import co.cask.cdap.metrics.process.KafkaConsumerMetaTable;

/**
 * Manages metric system datasets.
 */
public interface MetricDatasetFactory {

  /**
   * @param resolution resolution of {@link FactTable}
   * @return A new instance of {@link FactTable}.
   */
  FactTable getOrCreateFactTable(int resolution);

  /**
   * @return A new instance of {@link AbstractConsumerMetaTable}.
   */
  AbstractConsumerMetaTable createConsumerMeta(
    Class<? extends AbstractMetricsProcessorService> metricsProcessorServiceClass);
}
