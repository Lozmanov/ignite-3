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

package org.apache.ignite.internal.placementdriver.event;

import org.apache.ignite.internal.event.CausalEventParameters;
import org.apache.ignite.internal.replicator.ReplicationGroupId;

/** Primary replica event parameters. There are properties which associate with a concrete primary replica. */
public class PrimaryReplicaEventParameters extends CausalEventParameters {
    private final ReplicationGroupId groupId;

    private final String leaseholder;

    /**
     * Constructor.
     *
     * @param causalityToken Causality token.
     * @param groupId Replication group ID.
     * @param leaseholder Leaseholder node consistent ID.
     */
    public PrimaryReplicaEventParameters(long causalityToken, ReplicationGroupId groupId, String leaseholder) {
        super(causalityToken);

        this.groupId = groupId;
        this.leaseholder = leaseholder;
    }

    /** Replication group ID. */
    public ReplicationGroupId groupId() {
        return groupId;
    }

    /** Returns leaseholder node consistent ID. */
    public String leaseholder() {
        return leaseholder;
    }
}
