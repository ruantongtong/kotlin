/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER", "CANNOT_OVERRIDE_INVISIBLE_MEMBER")
package kotlin.internal.jdk8

import java.lang.reflect.Method
import java.util.regex.MatchResult
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.internal.PlatformImplementations
import kotlin.internal.jdk7.JDK7PlatformImplementations
import kotlin.random.Random
import kotlin.random.jdk8.PlatformThreadLocalRandom

internal open class JDK8PlatformImplementations : JDK7PlatformImplementations() {

    override fun getMatchResultNamedGroup(matchResult: MatchResult, name: String): MatchGroup? {
        val group = getMatchResultNamedGroupIfSupported(matchResult, name)
        if (group is MatchGroup?) return group
        throw UnsupportedOperationException("Retrieving groups by name is not supported on this platform.")
    }

    override fun getMatchResultNamedGroupIfSupported(matchResult: MatchResult, name: String): Any? {
        val matcher = matchResult as? Matcher ?: return Any()

        val range = matcher.start(name) until matcher.end(name)
        return if (range.start >= 0)
            MatchGroup(matcher.group(name), range)
        else
            null
    }

    private val namedGroupsAccessor: Method? by lazy {
        try {
            Pattern::class.java.getDeclaredMethod("namedGroups").apply { isAccessible = true }
        } catch (e: Exception) {
            if (e is NoSuchMethodException || e is SecurityException) null else throw e
        }
    }

    override fun getPatternNamedGroupsMap(pattern: Pattern): Map<String, Int>? =
        namedGroupsAccessor?.invoke(pattern) as Map<String, Int>?

    override fun defaultPlatformRandom(): Random = PlatformThreadLocalRandom()

}
