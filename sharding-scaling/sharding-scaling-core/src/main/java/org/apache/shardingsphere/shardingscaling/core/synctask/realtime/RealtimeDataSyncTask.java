/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.shardingscaling.core.synctask.realtime;

import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.shardingscaling.core.config.ScalingContext;
import org.apache.shardingsphere.shardingscaling.core.config.SyncConfiguration;
import org.apache.shardingsphere.shardingscaling.core.controller.SyncProgress;
import org.apache.shardingsphere.shardingscaling.core.controller.task.ReportCallback;
import org.apache.shardingsphere.shardingscaling.core.datasource.DataSourceManager;
import org.apache.shardingsphere.shardingscaling.core.execute.engine.SyncTaskExecuteCallback;
import org.apache.shardingsphere.shardingscaling.core.execute.executor.SyncExecutorGroup;
import org.apache.shardingsphere.shardingscaling.core.execute.executor.channel.DistributionChannel;
import org.apache.shardingsphere.shardingscaling.core.execute.executor.position.LogPositionManager;
import org.apache.shardingsphere.shardingscaling.core.execute.executor.position.LogPositionManagerFactory;
import org.apache.shardingsphere.shardingscaling.core.execute.executor.dumper.Dumper;
import org.apache.shardingsphere.shardingscaling.core.execute.executor.dumper.DumperFactory;
import org.apache.shardingsphere.shardingscaling.core.execute.executor.record.Record;
import org.apache.shardingsphere.shardingscaling.core.execute.executor.writer.Writer;
import org.apache.shardingsphere.shardingscaling.core.execute.executor.writer.WriterFactory;
import org.apache.shardingsphere.shardingscaling.core.synctask.SyncTask;
import org.apache.shardingsphere.underlying.common.database.metadata.DataSourceMetaData;

import java.util.ArrayList;
import java.util.List;

/**
 * Realtime data execute task.
 */
@Slf4j
public final class RealtimeDataSyncTask implements SyncTask {
    
    private final SyncConfiguration syncConfiguration;
    
    private final DataSourceManager dataSourceManager;
    
    private final String syncTaskId;
    
    private LogPositionManager logPositionManager;
    
    private Dumper dumper;
    
    private long delayMillisecond;
    
    public RealtimeDataSyncTask(final SyncConfiguration syncConfiguration, final DataSourceManager dataSourceManager) {
        this.syncConfiguration = syncConfiguration;
        this.dataSourceManager = dataSourceManager;
        DataSourceMetaData dataSourceMetaData = syncConfiguration.getReaderConfiguration().getDataSourceConfiguration().getDataSourceMetaData();
        syncTaskId = String.format("realtime-%s", null != dataSourceMetaData.getCatalog() ? dataSourceMetaData.getCatalog() : dataSourceMetaData.getSchema());
    }
    
    @Override
    public void prepare() {
        this.logPositionManager = instanceLogPositionManager();
        logPositionManager.getCurrentPosition();
    }
    
    private LogPositionManager instanceLogPositionManager() {
        return LogPositionManagerFactory.newInstanceLogManager(
                syncConfiguration.getReaderConfiguration().getDataSourceConfiguration().getDatabaseType().getName(),
                dataSourceManager.getDataSource(syncConfiguration.getReaderConfiguration().getDataSourceConfiguration()));
    }
    
    @Override
    public void start(final ReportCallback callback) {
        syncConfiguration.getReaderConfiguration().setTableNameMap(syncConfiguration.getTableNameMap());
        SyncExecutorGroup syncExecutorGroup = new SyncExecutorGroup(new SyncTaskExecuteCallback(this.getClass().getSimpleName(), syncTaskId, callback));
        instanceSyncExecutors(syncExecutorGroup);
        ScalingContext.getInstance().getSyncTaskExecuteEngine().submitGroup(syncExecutorGroup);
    }
    
    private void instanceSyncExecutors(final SyncExecutorGroup syncExecutorGroup) {
        dumper = DumperFactory.newInstanceLogDumper(syncConfiguration.getReaderConfiguration(), logPositionManager.getCurrentPosition());
        List<Writer> writers = instanceWriters();
        DistributionChannel channel = instanceChannel(writers);
        dumper.setChannel(channel);
        for (Writer each : writers) {
            each.setChannel(channel);
        }
        syncExecutorGroup.setChannel(channel);
        syncExecutorGroup.addSyncExecutor(dumper);
        syncExecutorGroup.addAllSyncExecutor(writers);
    }
    
    private List<Writer> instanceWriters() {
        List<Writer> result = new ArrayList<>(syncConfiguration.getConcurrency());
        for (int i = 0; i < syncConfiguration.getConcurrency(); i++) {
            result.add(WriterFactory.newInstance(syncConfiguration.getWriterConfiguration(), dataSourceManager));
        }
        return result;
    }
    
    private DistributionChannel instanceChannel(final List<Writer> writers) {
        return new DistributionChannel(writers.size(), records -> {
            Record lastHandledRecord = records.get(records.size() - 1);
            logPositionManager.updateCurrentPosition(lastHandledRecord.getLogPosition());
            delayMillisecond = System.currentTimeMillis() - lastHandledRecord.getCommitTime();
        });
    }
    
    @Override
    public void stop() {
        if (null != dumper) {
            dumper.stop();
            dumper = null;
        }
    }
    
    @Override
    public SyncProgress getProgress() {
        return new RealTimeDataSyncTaskProgress(syncTaskId, delayMillisecond, logPositionManager.getCurrentPosition());
    }
}
