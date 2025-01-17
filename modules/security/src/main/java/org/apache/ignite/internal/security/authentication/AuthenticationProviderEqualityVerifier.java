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

package org.apache.ignite.internal.security.authentication;

import org.apache.ignite.internal.security.authentication.basic.BasicAuthenticationProviderView;
import org.apache.ignite.internal.security.authentication.configuration.AuthenticationProviderView;
import org.jetbrains.annotations.Nullable;

/**
 * Equality verifier for {@link AuthenticationProviderView}.
 */
public class AuthenticationProviderEqualityVerifier {
    /**
     * Checks if two {@link AuthenticationProviderView} are equal.
     *
     * @param o1 First object.
     * @param o2 Second object.
     * @return {@code true} if objects are equal, {@code false} otherwise.
     */
    public static boolean areEqual(@Nullable AuthenticationProviderView o1, @Nullable AuthenticationProviderView o2) {
        if (o1 == o2) {
            return true;
        }

        if (o1 == null || o2 == null) {
            return false;
        }

        if (o1.getClass() != o2.getClass()) {
            return false;
        }

        if (!o1.type().equals(o2.type())) {
            return false;
        }

        if (!o1.name().equals(o2.name())) {
            return false;
        }

        if (o1 instanceof BasicAuthenticationProviderView) {
            return areEqual((BasicAuthenticationProviderView) o1, (BasicAuthenticationProviderView) o2);
        }

        return false;
    }

    private static boolean areEqual(BasicAuthenticationProviderView o1, BasicAuthenticationProviderView o2) {
        return o1.username().equals(o2.username()) && o1.password().equals(o2.password());
    }
}
