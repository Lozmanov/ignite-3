/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.sql.engine.prepare;

import org.apache.ignite.internal.sql.engine.SqlQueryType;
import org.apache.ignite.internal.sql.engine.rel.IgniteRel;
import org.apache.ignite.sql.ResultSetMetadata;
import org.jetbrains.annotations.Nullable;

/**
 * Regular query or DML.
 */
public class MultiStepPlan implements QueryPlan {

    private final PlanId id;

    private final SqlQueryType type;

    private final ResultSetMetadata meta;

    private final IgniteRel root;

    /** Constructor. */
    public MultiStepPlan(PlanId id, SqlQueryType type, IgniteRel root, ResultSetMetadata meta) {
        this.id = id;
        this.type = type;
        this.root = root;
        this.meta = meta;
    }

    /** {@inheritDoc} */
    @Override
    public PlanId id() {
        return id;
    }

    /** {@inheritDoc} */
    @Override
    public ResultSetMetadata metadata() {
        return meta;
    }

    /** {@inheritDoc} */
    @Override
    @Nullable
    public SqlQueryType type() {
        return type;
    }

    /** Returns root of the query tree. */
    public IgniteRel root() {
        return root;
    }
}
