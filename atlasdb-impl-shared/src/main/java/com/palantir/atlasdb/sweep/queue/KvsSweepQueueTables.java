/*
 * Copyright 2018 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.sweep.queue;

import java.util.function.Supplier;

import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.util.LazyRecomputingSupplier;

public final class KvsSweepQueueTables {
    final SweepableCells sweepableCells;
    final SweepableTimestamps sweepableTimestamps;
    final KvsSweepQueueProgress progress;

    private KvsSweepQueueTables(SweepableCells cells, SweepableTimestamps timestamps, KvsSweepQueueProgress progress) {
        this.sweepableCells = cells;
        this.sweepableTimestamps = timestamps;
        this.progress = progress;
    }

    public static KvsSweepQueueTables create(KeyValueService kvs, Supplier<Integer> shardsConfig) {
        KvsSweepQueueProgress progress = new KvsSweepQueueProgress(kvs);
        Supplier<Integer> shards = new LazyRecomputingSupplier<>(shardsConfig, progress::updateNumberOfShards);
        WriteInfoPartitioner partitioner = new WriteInfoPartitioner(kvs, shards);
        SweepableCells cells = new SweepableCells(kvs, partitioner);
        SweepableTimestamps timestamps = new SweepableTimestamps(kvs, partitioner);
        return new KvsSweepQueueTables(cells, timestamps, progress);
    }

}
