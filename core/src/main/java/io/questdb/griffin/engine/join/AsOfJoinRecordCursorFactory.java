/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2024 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin.engine.join;

import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.ColumnFilter;
import io.questdb.cairo.ColumnTypes;
import io.questdb.cairo.RecordSink;
import io.questdb.cairo.map.Map;
import io.questdb.cairo.map.MapFactory;
import io.questdb.cairo.map.MapKey;
import io.questdb.cairo.map.MapRecord;
import io.questdb.cairo.map.MapValue;
import io.questdb.cairo.map.RecordValueSink;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.RecordCursorFactory;
import io.questdb.cairo.sql.RecordMetadata;
import io.questdb.cairo.sql.SqlExecutionCircuitBreaker;
import io.questdb.griffin.PlanSink;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.model.JoinContext;
import io.questdb.std.IntList;
import io.questdb.std.Misc;
import io.questdb.std.Transient;

public class AsOfJoinRecordCursorFactory extends AbstractJoinRecordCursorFactory {
    private final IntList columnIndex;
    private final AsOfJoinRecordCursor cursor;
    private final RecordSink masterKeySink;
    private final RecordSink slaveKeySink;

    public AsOfJoinRecordCursorFactory(
            CairoConfiguration configuration,
            RecordMetadata metadata,
            RecordCursorFactory masterFactory,
            RecordCursorFactory slaveFactory,
            @Transient ColumnTypes mapKeyTypes,
            @Transient ColumnTypes mapValueTypes,
            @Transient ColumnTypes slaveColumnTypes,
            RecordSink masterKeySink,
            RecordSink slaveKeySink,
            int columnSplit,
            RecordValueSink slaveValueSink,
            IntList columnIndex, // this column index will be used to retrieve symbol tables from underlying slave
            JoinContext joinContext,
            ColumnFilter masterTableKeyColumns,
            long tolerance, // New parameter for time tolerance
            boolean matchNearest // New parameter for nearest match
    ) {
        super(metadata, joinContext, masterFactory, slaveFactory);
        try {
            this.masterKeySink = masterKeySink;
            this.slaveKeySink = slaveKeySink;
            Map joinKeyMap = MapFactory.createUnorderedMap(configuration, mapKeyTypes, mapValueTypes);
            int slaveWrappedOverMaster = slaveColumnTypes.getColumnCount() - masterTableKeyColumns.getColumnCount();
            this.cursor = new AsOfJoinRecordCursor(
                    columnSplit,
                    joinKeyMap,
                    NullRecordFactory.getInstance(slaveColumnTypes),
                    masterFactory.getMetadata().getTimestampIndex(),
                    slaveFactory.getMetadata().getTimestampIndex(),
                    slaveValueSink,
                    masterTableKeyColumns,
                    slaveWrappedOverMaster,
                    columnIndex,
                    tolerance, // Pass tolerance to cursor
                    matchNearest // Pass matchNearest to cursor
            );
            this.columnIndex = columnIndex;
        } catch (Throwable th) {
            close();
            throw th;
        }
    }

    @Override
    public boolean followedOrderByAdvice() {
        return masterFactory.followedOrderByAdvice();
    }

    @Override
    public RecordCursor getCursor(SqlExecutionContext executionContext) throws SqlException {
        // Forcefully disable column pre-touch for nested filter queries.
        executionContext.setColumnPreTouchEnabled(false);
        RecordCursor masterCursor = masterFactory.getCursor(executionContext);
        RecordCursor slaveCursor = null;
        try {
            slaveCursor = slaveFactory.getCursor(executionContext);
            cursor.of(masterCursor, slaveCursor);
            return cursor;
        } catch (Throwable e) {
            Misc.free(slaveCursor);
            Misc.free(masterCursor);
            Misc.free(cursor);
            throw e;
        }
    }

    @Override
    public int getScanDirection() {
        return masterFactory.getScanDirection();
    }

    @Override
    public boolean recordCursorSupportsRandomAccess() {
        return false;
    }

    @Override
    public void toPlan(PlanSink sink) {
        sink.type("AsOf Join");
        sink.attr("condition").val(joinContext);
        sink.child(masterFactory);
        sink.child(slaveFactory);
    }

    @Override
    protected void _close() {
        Misc.freeIfCloseable(getMetadata());
        Misc.free(masterFactory);
        Misc.free(slaveFactory);
        Misc.free(cursor);
    }

    private class AsOfJoinRecordCursor extends AbstractSymbolWrapOverCursor {
        private final Map joinKeyMap;
        private final int masterTimestampIndex;
        private final SymbolWrapOverJoinRecord record;
        private final int slaveTimestampIndex;
        private final RecordValueSink valueSink;
        private final long tolerance;
        private final boolean matchNearest;
        private boolean isMasterHasNextPending;
        private boolean isOpen;
        private boolean masterHasNext;
        private Record masterRecord;
        private Record slaveRecord;

        public AsOfJoinRecordCursor(
                int columnSplit,
                Map joinKeyMap,
                Record nullRecord,
                int masterTimestampIndex,
                int slaveTimestampIndex,
                RecordValueSink valueSink,
                ColumnFilter masterTableKeyColumns,
                int slaveWrappedOverMaster,
                IntList slaveColumnIndex,
                long tolerance, // New parameter for time tolerance
                boolean matchNearest // New parameter for nearest match
        ) {
            super(columnSplit, slaveWrappedOverMaster, masterTableKeyColumns, slaveColumnIndex);
            this.record = new SymbolWrapOverJoinRecord(columnSplit, nullRecord, slaveWrappedOverMaster, masterTableKeyColumns);
            this.joinKeyMap = joinKeyMap;
            this.masterTimestampIndex = masterTimestampIndex;
            this.slaveTimestampIndex = slaveTimestampIndex;
            this.valueSink = valueSink;
            this.tolerance = tolerance; // Initialize tolerance
            this.matchNearest = matchNearest; // Initialize matchNearest
            this.isOpen = true;
        }

        @Override
        public void calculateSize(SqlExecutionCircuitBreaker circuitBreaker, Counter counter) {
            masterCursor.calculateSize(circuitBreaker, counter);
        }

        @Override
        public void close() {
            if (isOpen) {
                joinKeyMap.close();
                isOpen = false;
                super.close();
            }
        }

        @Override
        public Record getRecord() {
            return record;
        }

        @Override
        public boolean hasNext() {
            if (isMasterHasNextPending) {
                masterHasNext = masterCursor.hasNext();
                isMasterHasNextPending = false;
            }
            
            if (masterHasNext) {
                final long masterTimestamp = masterRecord.getTimestamp(masterTimestampIndex);
                MapKey key;
                MapValue value;
                joinKeyMap.clear(); // Clear previous matches

                if (matchNearest) {
                    // Track the best match for nearest timestamp
                    long bestDiff = Long.MAX_VALUE;
                    Record bestMatch = null;
                    
                    while (slaveCursor.hasNext()) {
                        long currentSlaveTimestamp = slaveRecord.getTimestamp(slaveTimestampIndex);
                        long timeDiff = Math.abs(currentSlaveTimestamp - masterTimestamp);
                        
                        if (timeDiff <= tolerance && timeDiff < bestDiff) {
                            bestDiff = timeDiff;
                            bestMatch = slaveRecord;
                        } else if (currentSlaveTimestamp > masterTimestamp) {
                            break; // No need to look further
                        }
                    }
                    
                    if (bestMatch != null) {
                        key = joinKeyMap.withKey();
                        key.put(bestMatch, slaveKeySink);
                        value = key.createValue();
                        valueSink.copy(bestMatch, value);
                    }
                } else {
                    // Standard ASOF join with tolerance
                    Record bestMatch = null;
                    while (slaveCursor.hasNext()) {
                        long currentSlaveTimestamp = slaveRecord.getTimestamp(slaveTimestampIndex);
                        if (currentSlaveTimestamp <= masterTimestamp) {
                            if ((masterTimestamp - currentSlaveTimestamp) <= tolerance) {
                                bestMatch = slaveRecord;
                            }
                        } else {
                            break;
                        }
                    }
                    
                    if (bestMatch != null) {
                        key = joinKeyMap.withKey();
                        key.put(bestMatch, slaveKeySink);
                        value = key.createValue();
                        valueSink.copy(bestMatch, value);
                    }
                }

                key = joinKeyMap.withKey();
                key.put(masterRecord, masterKeySink);
                value = key.findValue();
                if (value != null) {
                    value.setMapRecordHere();
                    record.hasSlave(true);
                } else {
                    record.hasSlave(false);
                }

                isMasterHasNextPending = true;
                return true;
            }
            return false;
        }

        @Override
        public long size() {
            return masterCursor.size();
        }

        @Override
        public void toTop() {
            joinKeyMap.clear();
            masterCursor.toTop();
            slaveCursor.toTop();
            isMasterHasNextPending = true;
        }

        private void of(RecordCursor masterCursor, RecordCursor slaveCursor) {
            if (!isOpen) {
                isOpen = true;
                joinKeyMap.reopen();
            }
            this.masterCursor = masterCursor;
            this.slaveCursor = slaveCursor;
            masterRecord = masterCursor.getRecord();
            slaveRecord = slaveCursor.getRecord();
            MapRecord mapRecord = joinKeyMap.getRecord();
            mapRecord.setSymbolTableResolver(slaveCursor, columnIndex);
            record.of(masterRecord, mapRecord);
            isMasterHasNextPending = true;
        }
    }
}
