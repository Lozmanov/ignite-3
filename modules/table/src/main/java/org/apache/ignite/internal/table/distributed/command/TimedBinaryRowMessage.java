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

package org.apache.ignite.internal.table.distributed.command;

import org.apache.ignite.internal.schema.BinaryRow;
import org.apache.ignite.internal.table.distributed.TableMessageGroup;
import org.apache.ignite.internal.table.distributed.replication.request.BinaryRowMessage;
import org.apache.ignite.network.NetworkMessage;
import org.apache.ignite.network.annotations.Transferable;
import org.jetbrains.annotations.Nullable;

/**
 * The message type represent a binary row and a timestamp.
 */
@Transferable(TableMessageGroup.TIMED_BINARY_ROW_MESSAGE)
public interface TimedBinaryRowMessage extends NetworkMessage {
    /**
     * Gets a binary row message.
     *
     * @return binary row message.
     */
    @Nullable BinaryRowMessage binaryRowMessage();

    /**
     * Gets a timestamp.
     *
     * @return Timestamp as long.
     */
    long timestamp();

    /**
     * Gets a binary row form this message or {@code null} if the binary row message is {@code null}.
     *
     * @return Binary row or {@code null}.
     */
    default @Nullable BinaryRow binaryRow() {
        return binaryRowMessage() == null ? null : binaryRowMessage().asBinaryRow();
    }
}
