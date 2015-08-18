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
package org.apache.ambari.server.controller.metrics.timeline.cache;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.sf.ehcache.constructs.blocking.UpdatingCacheEntryFactory;
import org.apache.ambari.server.configuration.ComponentSSLConfiguration;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.controller.internal.URLStreamProvider;
import org.apache.ambari.server.controller.metrics.timeline.MetricsRequestHelper;
import org.apache.ambari.server.controller.spi.TemporalInfo;
import org.apache.ambari.server.controller.utilities.StreamProvider;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetric;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetrics;
import org.apache.http.client.utils.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Singleton
public class TimelineMetricCacheEntryFactory implements UpdatingCacheEntryFactory {
  private final static Logger LOG = LoggerFactory.getLogger(TimelineMetricCacheEntryFactory.class);
  // Not declared final to ease unit test code and allow streamProvider
  // injection
  private MetricsRequestHelper requestHelperForGets;
  private MetricsRequestHelper requestHelperForUpdates;
  private final Long BUFFER_TIME_DIFF_CATCHUP_INTERVAL;

  @Inject
  public TimelineMetricCacheEntryFactory(Configuration configuration) {
    // Longer timeout for first cache miss
    requestHelperForGets = new MetricsRequestHelper(new URLStreamProvider(
      configuration.getMetricsRequestConnectTimeoutMillis(),
      configuration.getMetricsRequestReadTimeoutMillis(),
      ComponentSSLConfiguration.instance()));

    // Timeout setting different from first request timeout
    // Allows stale data to be returned at the behest of performance.
    requestHelperForUpdates = new MetricsRequestHelper(new URLStreamProvider(
      configuration.getMetricsRequestConnectTimeoutMillis(),
      configuration.getMetricsRequestIntervalReadTimeoutMillis(),
      ComponentSSLConfiguration.instance()));

    BUFFER_TIME_DIFF_CATCHUP_INTERVAL = configuration.getMetricRequestBufferTimeCatchupInterval();
  }

  /**
   * This method is called on a get element from cache call when key is not
   * found in cache, returns a value for the key to be cached.
   *
   * @param key @org.apache.ambari.server.controller.metrics.timeline.cache.TimelineAppMetricCacheKey
   * @return @org.apache.hadoop.metrics2.sink.timeline.TimelineMetrics
   * @throws Exception
   */
  @Override
  public Object createEntry(Object key) throws Exception {
    LOG.debug("Creating cache entry since none exists, key = " + key);
    TimelineAppMetricCacheKey metricCacheKey = (TimelineAppMetricCacheKey) key;

    TimelineMetrics timelineMetrics =
      requestHelperForGets.fetchTimelineMetrics(metricCacheKey.getSpec());

    TimelineMetricsCacheValue value = null;

    if (timelineMetrics != null && !timelineMetrics.getMetrics().isEmpty()) {
      Map<String, TimelineMetric> cacheValue =
        new HashMap<String, TimelineMetric>(timelineMetrics.getMetrics().size());
      for (TimelineMetric metric : timelineMetrics.getMetrics()) {
        cacheValue.put(metric.getMetricName(), metric);
      }

      value = new TimelineMetricsCacheValue(
        metricCacheKey.getTemporalInfo().getStartTime(),
        metricCacheKey.getTemporalInfo().getEndTime(),
        cacheValue // Null or empty should prompt a refresh
      );

      LOG.debug("Created cache entry: " + value);
    }

    return value;
  }

  /**
   * Called on a get call for existing values in the cache,
   * the necessary locking code is present in the get call and this call
   * should update the value of the cache entry before returning.
   *
   * @param key @org.apache.ambari.server.controller.metrics.timeline.cache.TimelineAppMetricCacheKey
   * @param value @org.apache.hadoop.metrics2.sink.timeline.TimelineMetrics
   * @throws Exception
   */
  @Override
  public void updateEntryValue(Object key, Object value) throws Exception {
    TimelineAppMetricCacheKey metricCacheKey = (TimelineAppMetricCacheKey) key;
    TimelineMetricsCacheValue existingMetrics = (TimelineMetricsCacheValue) value;

    LOG.debug("Updating cache entry, key: " + key + ", with value = " + value);

    Long existingSeriesStartTime = existingMetrics.getStartTime();
    Long existingSeriesEndTime = existingMetrics.getEndTime();

    TemporalInfo newTemporalInfo = metricCacheKey.getTemporalInfo();
    Long requestedStartTime = newTemporalInfo.getStartTime();
    Long requestedEndTime = newTemporalInfo.getEndTime();

    // Calculate new start and end times
    URIBuilder uriBuilder = new URIBuilder(metricCacheKey.getSpec());
    Long newStartTime = getRefreshRequestStartTime(existingSeriesStartTime,
      existingSeriesEndTime, requestedStartTime);
    Long newEndTime = getRefreshRequestEndTime(existingSeriesStartTime,
      existingSeriesEndTime, requestedEndTime);

    // Cover complete overlap scenario
    // time axis: |-------- exSt ----- reqSt ------ exEnd ----- extEnd ---------|
    if (newEndTime > newStartTime &&
       !(newStartTime.equals(existingSeriesStartTime) &&
       newEndTime.equals(existingSeriesEndTime))) {

      LOG.debug("Existing cached timeseries startTime = " +
        new Date(getMillisecondsTime(existingSeriesStartTime)) + ", endTime = " +
        new Date(getMillisecondsTime(existingSeriesEndTime)));

      LOG.debug("Requested timeseries startTime = " +
        new Date(getMillisecondsTime(newStartTime)) + ", endTime = " +
        new Date(getMillisecondsTime(newEndTime)));

      // Update spec with new start and end time
      uriBuilder.setParameter("startTime", String.valueOf(newStartTime));
      uriBuilder.setParameter("endTime", String.valueOf(newEndTime));

      try {
        TimelineMetrics newTimeSeries = requestHelperForUpdates.fetchTimelineMetrics(uriBuilder.toString());

        // Update existing time series with new values
        updateTimelineMetricsInCache(newTimeSeries, existingMetrics,
          getMillisecondsTime(requestedStartTime),
          getMillisecondsTime(requestedEndTime));

        // Replace old boundary values
        existingMetrics.setStartTime(requestedStartTime);
        existingMetrics.setEndTime(requestedEndTime);

      } catch (IOException io) {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Exception retrieving metrics.", io);
        }
      }
    } else {
      LOG.debug("Skip updating cache with new startTime = " +
        new Date(getMillisecondsTime(newStartTime)) +
        ", new endTime = " + new Date(getMillisecondsTime(newEndTime)));
    }
  }

  /**
   * Update cache with new timeseries data
   */
  protected void updateTimelineMetricsInCache(TimelineMetrics newMetrics,
      TimelineMetricsCacheValue timelineMetricsCacheValue,
      Long requestedStartTime, Long requestedEndTime) {

    Map<String, TimelineMetric> existingTimelineMetricMap = timelineMetricsCacheValue.getTimelineMetrics();

    // NOTE: Metrics names so far are unique, the Map optimization avoids
    // multiple iterations of the List
    for (TimelineMetric timelineMetric : newMetrics.getMetrics()) {
      if (LOG.isTraceEnabled()) {
        TreeMap<Long, Double> sortedMetrics = new TreeMap<Long, Double>(timelineMetric.getMetricValues());

        LOG.trace("New metric: " + timelineMetric.getMetricName() +
          " # " + timelineMetric.getMetricValues().size() + ", startTime = " +
          sortedMetrics.firstKey() + ", endTime = " + sortedMetrics.lastKey());
      }


      TimelineMetric existingMetric = existingTimelineMetricMap.get(timelineMetric.getMetricName());

      if (existingMetric != null) {
        Map<Long, Double> existingMetricValues = existingMetric.getMetricValues();
        LOG.trace("Existing metric: " + timelineMetric.getMetricName() +
          " # " + existingMetricValues.size());

        Iterator<Map.Entry<Long, Double>> valueIterator = existingMetricValues.entrySet().iterator();

        // Remove old values
        // Assumption: All return value are millis
        while (valueIterator.hasNext()) {
          Map.Entry<Long, Double> metricEntry = valueIterator.next();
          if (metricEntry.getKey() < requestedStartTime
              || metricEntry.getKey() > requestedEndTime) {
            valueIterator.remove();
          }
        }

        // Add new ones
        existingMetricValues.putAll(timelineMetric.getMetricValues());

        if (LOG.isTraceEnabled()) {
          TreeMap<Long, Double> sortedMetrics = new TreeMap<Long, Double>(existingMetricValues);
          LOG.trace("Merged metric: " + timelineMetric.getMetricName() + ", " +
            "Final size: " + existingMetricValues.size() + ", startTime = " +
            sortedMetrics.firstKey() + ", endTime = " + sortedMetrics.lastKey());
        }
      } else {
        existingTimelineMetricMap.put(timelineMetric.getMetricName(), timelineMetric);
      }
    }
  }

  // Scenario: Regular graph updates
  // time axis: |-------- exSt ----- reqSt ------ exEnd ----- reqEnd ---------|
  // Scenario: Selective graph updates
  // time axis: |-------- exSt ----- exEnd ------ reqSt ----- reqEnd ---------|
  // Scenario: Extended time window
  // time axis: |-------- reSt ----- exSt ------- extEnd ---- reqEnd ---------|
  protected Long getRefreshRequestStartTime(Long existingSeriesStartTime,
      Long existingSeriesEndTime, Long requestedStartTime) {
    Long diff = requestedStartTime - existingSeriesEndTime;
    Long startTime = requestedStartTime;

    if (diff < 0 && requestedStartTime > existingSeriesStartTime) {
      // Regular graph updates
      // Overlapping timeseries data refresh only new part
      // Account for missing data on the trailing edge due to buffering
      startTime = getTimeShiftedStartTime(existingSeriesEndTime);
    }

    LOG.trace("Requesting timeseries data with new startTime = " +
      new Date(getMillisecondsTime(startTime)));

    return startTime;
  }

  // Scenario: Regular graph updates
  // time axis: |-------- exSt ----- reqSt ------ exEnd ----- reqEnd ---------|
  // Scenario: Old data request /w overlap
  // time axis: |-------- reqSt ----- exSt ------ reqEnd ----- extEnd --------|
  // Scenario: Very Old data request /wo overlap
  // time axis: |-------- reqSt ----- reqEnd ------ exSt ----- extEnd --------|
  protected Long getRefreshRequestEndTime(Long existingSeriesStartTime,
      Long existingSeriesEndTime, Long requestedEndTime) {
    Long endTime = requestedEndTime;
    Long diff = requestedEndTime - existingSeriesEndTime;
    if (diff < 0 && requestedEndTime > existingSeriesStartTime) {
      // End time overlaps existing timeseries
      // Get only older data that might not be in the cache
      endTime = existingSeriesStartTime;
    }

    LOG.trace("Requesting timeseries data with new endTime = " +
      new Date(getMillisecondsTime(endTime)));
    return endTime;
  }

  /**
   * Time shift by a constant taking into account Epoch vs millis
   */
  private long getTimeShiftedStartTime(long startTime) {
    if (startTime < 9999999999l) {
      // Epoch time
      return startTime - (BUFFER_TIME_DIFF_CATCHUP_INTERVAL / 1000);
    } else {
      return startTime - BUFFER_TIME_DIFF_CATCHUP_INTERVAL;
    }
  }

  private long getMillisecondsTime(long time) {
    if (time < 9999999999l) {
      return time * 1000;
    } else {
      return time;
    }
  }
}