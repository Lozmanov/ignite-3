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

// ReSharper disable ParameterOnlyUsedForPreconditionCheck.Global
namespace Apache.Ignite.Internal.Common
{
    using System;
    using System.Diagnostics.CodeAnalysis;
    using System.Runtime.CompilerServices;

    /// <summary>
    /// Arguments check helpers.
    /// </summary>
    internal static class IgniteArgumentCheck
    {
        /// <summary>
        /// Throws an ArgumentException if specified arg is null or empty string.
        /// </summary>
        /// <param name="arg">The argument.</param>
        /// <param name="argName">Name of the argument.</param>
        /// <returns>Argument.</returns>
        public static string NotNullOrEmpty(string arg, [CallerArgumentExpression("arg")] string? argName = null)
        {
            // The logic below matches ArgumentException.ThrowIfNullOrEmpty on .NET 8.
            if (string.IsNullOrEmpty(arg))
            {
                ThrowNullOrEmptyException(arg, argName);
            }

            return arg;
        }

        /// <summary>
        /// Throws an ArgumentException if specified condition is false.
        /// </summary>
        /// <param name="condition">Condition.</param>
        /// <param name="argName">Name of the argument.</param>
        /// <param name="message">Message.</param>
        public static void Ensure(bool condition, string argName, string message)
        {
            if (!condition)
            {
                throw new ArgumentException($"'{argName}' argument is invalid: {message}", argName);
            }
        }

        [DoesNotReturn]
        private static void ThrowNullOrEmptyException(string? argument, string? paramName)
        {
            ArgumentNullException.ThrowIfNull(argument, paramName);
            throw new ArgumentException("The value cannot be an empty string.", paramName);
        }
    }
}
