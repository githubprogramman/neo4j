/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.runtime.interpreted.pipes

import org.neo4j.cypher.internal.runtime.ExecutionContext
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.AggregationExpression
import org.neo4j.cypher.internal.runtime.interpreted.pipes.AggregationPipe.AggregationTableFactory
import org.neo4j.cypher.internal.runtime.interpreted.pipes.DistinctPipe.GroupingCol
import org.neo4j.values.AnyValue
import org.neo4j.values.virtual.{ListValue, VirtualValues}

/**
  * This abstracts all the logic of aggregating, potentially per group.
  */
abstract class AggregationPipe(source: Pipe,
                               tableFactory: AggregationTableFactory)
  extends PipeWithSource(source) {
  tableFactory.registerOwningPipe(this)
}

object AggregationPipe {

  /**
    * An AggregationTable is initialized by calling `clear`, followed by `processRow` for each row.
    * The result iterator is finally obtained by calling `result`.
    */
  trait AggregationTable {
    def clear(): Unit

    def processRow(row: ExecutionContext): Unit

    def result(): Iterator[ExecutionContext]
  }

  /**
    * A Factory to obtain [[AggregationTable]]s at runtime.
    */
  trait AggregationTableFactory {
    def table(state: QueryState, executionContextFactory: ExecutionContextFactory): AggregationTable

    def registerOwningPipe(pipe: Pipe): Unit
  }

  case class AggregatingCol(key: String, expression: AggregationExpression)

  /**
    * Precompute a function that computes the grouping key of a row.
    * The reason we precompute this is that we can specialize code depending on the amount of grouping keys.
    */
  def computeGroupingFunction(groupingColumns: Array[GroupingCol]): (ExecutionContext, QueryState) => AnyValue = {
    groupingColumns.length match {
      case 0 =>
        (_, _) => null
      case 1 =>
        val firstExpression = groupingColumns.head.expression
        (ctx, state) => firstExpression(ctx, state)

      case 2 =>
        val e1 = groupingColumns.head.expression
        val e2 = groupingColumns.last.expression
        (ctx, state) => VirtualValues.list(e1(ctx, state), e2(ctx, state))

      case 3 =>
        val e1 = groupingColumns.head.expression
        val e2 = groupingColumns.tail.head.expression
        val e3 = groupingColumns.last.expression
        (ctx, state) => VirtualValues.list(e1(ctx, state), e2(ctx, state), e3(ctx, state))

      case _ =>
        val expressions = groupingColumns.map(_.expression)
        (ctx, state) => VirtualValues.list(expressions.map(e => e(ctx, state)): _*)
    }
  }

  /**
    * Precompute a function that adds the grouping key columns to a result row.
    * The reason we precompute this is that we can specialize code depending on the amount of grouping keys.
    */
  def computeAddKeysToResultMapFunction(groupingColumns: Array[GroupingCol]): (ExecutionContext, AnyValue) => Unit =
    groupingColumns.length match {
      case 0 =>
        // Do nothing
        (_, _) => ()
      case 1 =>
        val key = groupingColumns.head.key
        (map, groupingKey) => map.set(key, groupingKey)
      case 2 =>
        val key = groupingColumns.head.key
        val key2 = groupingColumns.last.key
        (map, groupingKey) => {
          val t2 = groupingKey.asInstanceOf[ListValue]
          map.set(key, t2.head())
          map.set(key2, t2.last())
        }
      case 3 =>
        val key = groupingColumns.head.key
        val key2 = groupingColumns(1).key
        val key3 = groupingColumns(2).key
        (map, groupingKey) => {
          val t3 = groupingKey.asInstanceOf[ListValue]
          map.set(key, t3.value(0))
          map.set(key2, t3.value(1))
          map.set(key3, t3.value(2))
        }
      case _ =>
        (map, groupingKey) => {
          val listOfValues = groupingKey.asInstanceOf[ListValue]
          var i = 0
          while (i < groupingColumns.length) {
            val k = groupingColumns(i).key
            val value: AnyValue = listOfValues.value(i)
            map.set(k, value)
            i += 1
          }
        }
    }
}
