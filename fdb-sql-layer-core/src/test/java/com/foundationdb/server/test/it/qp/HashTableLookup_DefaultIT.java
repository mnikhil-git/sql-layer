/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.server.test.it.qp;

import com.foundationdb.qp.operator.Operator;
import com.foundationdb.qp.row.Row;
import com.foundationdb.qp.rowtype.RowType;
import com.foundationdb.qp.rowtype.TableRowType;
import com.foundationdb.server.collation.AkCollator;
import com.foundationdb.server.types.texpressions.TPreparedBoundField;
import com.foundationdb.server.types.texpressions.TPreparedExpression;
import com.foundationdb.server.types.texpressions.TPreparedField;
import org.junit.Test;

import java.util.*;

import static com.foundationdb.qp.operator.API.*;

public class HashTableLookup_DefaultIT extends OperatorITBase {

    static int ROW_BINDING_POSITION = 100;
    static int TABLE_BINDING_POSITION = 200;
    private int fullAddress;
    TableRowType fullAddressRowType;
    private RowType projectRowType;
    List<TPreparedExpression> genericExpressionList;
    List<TPreparedExpression> emptyExpressionList;
    List<TPreparedBoundField> emptyBoundExpressionList;

    @Override
    protected void setupCreateSchema() {
        super.setupCreateSchema();
        fullAddress = createTable(
                "schema", "fullAddress",
                "aid int not null primary key",
                "cid int",
                "address varchar(100)",
                "name varchar(20)");
        createIndex("schema", "fullAddress", "name", "name");
    }

    @Override
    protected void setupPostCreateSchema() {
        super.setupPostCreateSchema();
        Row[] db = new Row[]{
                row(customer, 1L, "northbridge"), // two orders, two addresses
                row(customer, 2L, "foundation"), // two orders, one address
                row(customer, 3L, "matrix"), // one order, two addresses
                row(customer, 4L, "atlas"), // two orders, no addresses
                row(customer, 5L, "highland"), // no orders, two addresses
                row(customer, 6L, "flybridge"), // no orders or addresses

                row(address, 5000L, 5L, "555 5000 st"),
                row(address, 5001L, 5L, "555 5001 st"),
                row(address, 1000L, 1L, "111 1000 st"),
                row(address, 1001L, 1L, "111 1001 st"),
                row(address, 3000L, 3L, "333 3000 st"),
                row(address, 3001L, 3L, "333 3001 st"),
                row(address, 2000L, 2L, "222 2000 st"),

                row(order, 300L, 3L, "tom"),
                row(order, 400L, 4L, "jack"),
                row(order, 401L, 4L, "jack"),
                row(order, 200L, 2L, "david"),
                row(order, 201L, 2L, "david"),
                row(order, 100L, 1L, "ori"),
                row(order, 101L, 1L, "ori"),

                row(item, 111L, null),
                row(item, 112L, null),
                row(item, 121L, 12L),
                row(item, 122L, 12L),
                row(item, 211L, null),
                row(item, 212L, 21L),
                row(item, 221L, 22L),
                row(item, 222L, null)
        };
        use(db);
        fullAddressRowType = schema.tableRowType(table(fullAddress));
        ciCollator = customerRowType.table().getColumn(1).getCollator();
        genericExpressionList = new ArrayList<>();
        genericExpressionList.add(new TPreparedField(customerRowType.typeAt(0), 0));
        emptyExpressionList = new ArrayList<>();
        emptyBoundExpressionList = new ArrayList<>();
    }

    /** Test argument HashJoinLookup_Default */

    @Test(expected = IllegalArgumentException.class)
    public void testHashJoinEmptyComparisonFields() {
        hashTableLookup_Default(customerRowType, emptyExpressionList,TABLE_BINDING_POSITION);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testHashJoinNullComparisonFields() {
        hashTableLookup_Default(customerRowType, null,  TABLE_BINDING_POSITION);
    }

    /** Test arguments using_HashTable  */

    @Test(expected = IllegalArgumentException.class)
    public void testUsingHashJoinRightInputNull() {
        using_HashTable(groupScan_Default(coi), customerRowType, genericExpressionList, TABLE_BINDING_POSITION, null, null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUsingHashJoinLeftInputNull() {
        using_HashTable(null, customerRowType, genericExpressionList, TABLE_BINDING_POSITION, groupScan_Default(coi), null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUsingHashJoinBothInputsNull() {
        using_HashTable(null, customerRowType,genericExpressionList,  TABLE_BINDING_POSITION, null, null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUsingHashJoinEmptyComparisonFields() {
        using_HashTable(groupScan_Default(coi), customerRowType, emptyExpressionList, TABLE_BINDING_POSITION, groupScan_Default(coi), null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUsingHashJoinNullComparisonFields() {
        using_HashTable(groupScan_Default(coi), customerRowType, null, TABLE_BINDING_POSITION, groupScan_Default(coi), null, null);
    }

    /** Hash join tests **/

    @Test
    public void testSingleColumnJoin() {
        int orderFieldsToCompare[] = {1};
        int customerFieldsToCompare[] = {0};
        Operator plan = hashJoinPlan(orderRowType, customerRowType,  orderFieldsToCompare,customerFieldsToCompare, null);
        Row[] expected = new Row[]{
                row(projectRowType, 100L, 1L, "ori","northbridge"),
                row(projectRowType, 101L, 1L, "ori", "northbridge"),
                row(projectRowType, 200L, 2L, "david", "foundation"),
                row(projectRowType, 201L, 2L, "david", "foundation"),
                row(projectRowType, 300L, 3L, "tom", "matrix"),
                row(projectRowType, 400L, 4L, "jack", "atlas"),
                row(projectRowType, 401L, 4L, "jack", "atlas"),
        };
        compareRows(expected, cursor(plan, queryContext, queryBindings));
    }

    @Test
    public void testMultiColumnNestedJoin() {
        int orderFieldsToCompare[] = {1};
        int customerFieldsToCompare[] = {0};
        Operator firstPlan = hashJoinPlan( orderRowType,customerRowType,  orderFieldsToCompare,customerFieldsToCompare, null);
        int secondHashFieldsToCompare[] = {1,2};
        List<AkCollator> secondCollators = Arrays.asList(null, ciCollator);
        Operator plan = hashJoinPlan(projectRowType,
                                     orderRowType,
                                     firstPlan,
                                     filter_Default(
                                             groupScan_Default(orderRowType.table().getGroup()),
                                             Collections.singleton(orderRowType)
                                     ),
                                     secondHashFieldsToCompare,
                                     secondHashFieldsToCompare,
                                     secondCollators
                                     );
        Row[] expected = new Row[]{
                row(projectRowType, 100L, 1L, "ori", "northbridge", 100L),
                row(projectRowType, 100L, 1L, "ori", "northbridge", 101L),
                row(projectRowType, 101L, 1L, "ori", "northbridge", 100L),
                row(projectRowType, 101L, 1L, "ori", "northbridge", 101L),
                row(projectRowType, 200L, 2L, "david", "foundation", 200L),
                row(projectRowType, 200L, 2L, "david", "foundation", 201L),
                row(projectRowType, 201L, 2L, "david", "foundation", 200L),
                row(projectRowType, 201L, 2L, "david", "foundation", 201L),
                row(projectRowType, 300L, 3L, "tom", "matrix", 300L),
                row(projectRowType, 400L, 4L, "jack", "atlas", 400L),
                row(projectRowType, 400L, 4L, "jack", "atlas", 401L),
                row(projectRowType, 401L, 4L, "jack", "atlas", 400L),
                row(projectRowType, 401L, 4L, "jack", "atlas", 401L),
        };
        compareRows(expected, cursor(plan, queryContext, queryBindings));
    }

    @Test
    public void testInnerJoin() {
        int addressFieldsToCompare[] = {1};
        int customerFieldsToCompare[] = {0};
        List<AkCollator> collators = Arrays.asList(ciCollator);
        Operator plan = hashJoinPlan(    addressRowType,
                                         customerRowType,
                                         addressFieldsToCompare,
                                         customerFieldsToCompare,
                                         collators);
        Row[] expected = new Row[]{
                row(fullAddressRowType, 1000L, 1L, "111 1000 st", "northbridge"),
                row(fullAddressRowType, 1001L, 1L, "111 1001 st", "northbridge"),
                row(fullAddressRowType, 2000L, 2L, "222 2000 st", "foundation"),
                row(fullAddressRowType, 3000L, 3L, "333 3000 st", "matrix"),
                row(fullAddressRowType, 3001L, 3L, "333 3001 st", "matrix"),
                row(fullAddressRowType, 5000L, 5L, "555 5000 st", "highland"),
                row(fullAddressRowType, 5001L, 5L, "555 5001 st", "highland"),
        };
        compareRows(expected, cursor(plan, queryContext, queryBindings));
    }

    @Test
    public void testAllMatch() {
        int orderFieldsToCompare[] = {0,1,2};
        List<AkCollator> collators = Arrays.asList(null,null,ciCollator);
        Operator plan = hashJoinPlan(orderRowType, orderRowType,  orderFieldsToCompare,orderFieldsToCompare, collators);
        Row[] expected = new Row[]{
                row(projectRowType, 100L, 1L, "ori"),
                row(projectRowType, 101L, 1L, "ori"),
                row(projectRowType, 200L, 2L, "david"),
                row(projectRowType, 201L, 2L, "david"),
                row(projectRowType, 300L, 3L, "tom"),
                row(projectRowType, 400L, 4L, "jack"),
                row(projectRowType, 401L, 4L, "jack"),
        };
        compareRows(expected, cursor(plan, queryContext, queryBindings));
    }

    @Test
    public void testNoMatch() {
        int orderFieldsToCompare[] = {0};
        Operator plan = hashJoinPlan(orderRowType, customerRowType,  orderFieldsToCompare,orderFieldsToCompare, null);
        Row[] expected = new Row[]{
        };
        compareRows(expected, cursor(plan, queryContext, queryBindings));
    }

    @Test
    public void testLeftOuterJoin() {
        int customerFieldsToCompare[] = {0};
        int orderFieldsToCompare[] = {1};
        Operator plan = hashJoinPlan(customerRowType, orderRowType,  customerFieldsToCompare,orderFieldsToCompare, null);
        Row[] expected = new Row[]{
                row(projectRowType, 1L, "northbridge", 100L, "ori"),
                row(projectRowType, 1L, "northbridge", 101L, "ori"),
                row(projectRowType, 2L, "foundation",200L, "david"),
                row(projectRowType, 2L, "foundation",201L, "david"),
                row(projectRowType, 3L, "matrix", 300L, "tom"),
                row(projectRowType, 4L, "atlas",  400L, "jack"),
                row(projectRowType, 4L, "atlas",  401L, "jack"),
                //row(customerRowType, 5L, "highland"),
                //row(customerRowType, 6L, "flybridge")
                //Left outer joins are taken care of in optimizer and not by these immedietly operators
        };
        compareRows(expected, cursor(plan, queryContext, queryBindings));
    }

    @Test
    public void testNullColumns() {
        int FieldsToCompare[] = {1};
        Operator plan = hashJoinPlan(itemRowType, itemRowType,  FieldsToCompare,FieldsToCompare, null);
        Row[] expected = new Row[]{
            /*
                row(projectRowType, 111L, null, 111L),
                row(projectRowType, 111L, null, 112L),
                row(projectRowType, 111L, null, 211L),
                row(projectRowType, 111L, null, 222L),
                row(projectRowType, 112L, null, 111L),
                row(projectRowType, 112L, null, 112L),
                row(projectRowType, 112L, null, 211L),
                row(projectRowType, 112L, null, 222L),
                row(projectRowType, 211L, null, 111L),
                row(projectRowType, 211L, null, 112L),
                row(projectRowType, 211L, null, 211L),
                row(projectRowType, 211L, null, 222L),
                row(projectRowType, 222L, null, 111L),
                row(projectRowType, 222L, null, 112L),
                row(projectRowType, 222L, null, 211L),
                row(projectRowType, 222L, null, 222L),
            */
                row(projectRowType, 121L, 12L, 121L),
                row(projectRowType, 121L, 12L, 122L),
                row(projectRowType, 122L, 12L, 121L),
                row(projectRowType, 122L, 12L, 122L),
                row(projectRowType, 212L, 21L, 212L),
                row(projectRowType, 221L, 22L, 221L)
        };
        compareRows(expected, cursor(plan, queryContext, queryBindings));
    }

    private Operator hashJoinPlan( RowType outerRowType,
                                   RowType innerRowType,
                                   int outerJoinFields[],
                                   int innerJoinFields[],
                                   List<AkCollator> collators) {
        return hashJoinPlan(outerRowType,
                     innerRowType,
                     filter_Default(
                            groupScan_Default(outerRowType.table().getGroup()),
                            Collections.singleton(outerRowType)
                     ),
                     filter_Default(
                            groupScan_Default(innerRowType.table().getGroup()),
                            Collections.singleton(innerRowType)
                     ),
                     outerJoinFields,
                     innerJoinFields,
                     collators
        );
    }

    private Operator hashJoinPlan( RowType outerRowType,
                                   RowType innerRowType,
                                   Operator outerStream,
                                   Operator innerStream,
                                   int outerJoinFields[],
                                   int innerJoinFields[],
                                   List<AkCollator> collators) {

        List<TPreparedExpression> expressions = new ArrayList<>();
        for( int i = 0; i < outerRowType.nFields(); i++){
            expressions.add(new TPreparedBoundField(outerRowType, ROW_BINDING_POSITION, i));
        }
        for( int i = 0, j = 0; i < innerRowType.nFields(); i++){
            if(j < innerJoinFields.length && innerJoinFields[j] == i) {
                j++;
            }else{
                expressions.add(new TPreparedField(innerRowType.typeAt(i), i));
            }
        }


        List<TPreparedExpression> outerExpressions = new ArrayList<>();
        for (int i : outerJoinFields){
            outerExpressions.add(new TPreparedBoundField(outerRowType, ROW_BINDING_POSITION, i));
        }
        List<TPreparedExpression> innerExpressions = new ArrayList<>();
        for(int i : innerJoinFields){
            innerExpressions.add(new TPreparedField(innerRowType.typeAt(i), i));
        }

        Operator project = project_Default(
                hashTableLookup_Default(
                        innerRowType,
                        outerExpressions,
                        TABLE_BINDING_POSITION
                ),
                innerRowType,
                expressions
        );

        projectRowType = project.rowType();

        return using_HashTable(
                innerStream,
                innerRowType,
                innerExpressions,
                TABLE_BINDING_POSITION++,
                map_NestedLoops(
                        outerStream,
                        project,
                        ROW_BINDING_POSITION++,
                        false,
                        1
                ),
                null, collators
        );
    }
}

