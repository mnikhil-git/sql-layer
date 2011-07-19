/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.ais.model;

import java.io.Serializable;
import java.util.*;

import com.akiban.ais.model.validation.AISInvariants;

public abstract class Table implements Serializable, ModelNames, Traversable, HasGroup
{
    public abstract boolean isUserTable();

    @Override
    public String toString()
    {
        return tableName.toString();
    }

    public static Table create(AkibanInformationSchema ais, Map<String, Object> map)
    {
        ais.checkMutability();
        String tableType = (String) map.get(table_tableType);
        String schemaName = (String) map.get(table_schemaName);
        String tableName = (String) map.get(table_tableName);
        Integer tableId = (Integer) map.get(table_tableId);
        String groupName = (String) map.get(table_groupName);
        Table table = null;
        if (tableType.equals("USER")) {
            table = UserTable.create(ais, schemaName, tableName, tableId);
        } else if (tableType.equals("GROUP")) {
            table = GroupTable.create(ais, schemaName, tableName, tableId);
        }
        if (table != null && groupName != null) {
            Group group = ais.getGroup(groupName);
            table.setGroup(group);
        }
        assert table != null;
        table.migrationUsage = MigrationUsage.values()[(Integer) map.get(table_migrationUsage)];
        return table;
    }

    public final Map<String, Object> map()
    {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(table_tableType, isGroupTable() ? "GROUP" : "USER");
        map.put(table_schemaName, getName().getSchemaName());
        map.put(table_tableName, getName().getTableName());
        map.put(table_tableId, getTableId());
        map.put(table_groupName, getGroup() == null ? null : getGroup().getName());
        map.put(table_migrationUsage, migrationUsage.ordinal());
        return map;
    }

    protected Table(AkibanInformationSchema ais, String schemaName, String tableName, Integer tableId)
    {
        this();
        ais.checkMutability();
        AISInvariants.checkNullName(schemaName, "Table", "schema name");
        AISInvariants.checkNullName(tableName, "Table", "table name");
        AISInvariants.checkDuplicateTables(ais, schemaName, tableName);

        this.ais = ais;
        this.tableName = new TableName(schemaName, tableName);
        this.tableId = tableId;
    }

    public AkibanInformationSchema getAIS()
    {
        return ais;
    }

    public boolean isGroupTable()
    {
        return !isUserTable();
    }

    public Integer getTableId()
    {
        return tableId;
    }

    /**
     * Temporary mutator so that prototype AIS management can renumber all
     * the tables once created.  Longer term we want to give the table
     * its ID when generated.
     *
     * @param tableId
     */
    public void setTableId(final int tableId)
    {
        this.tableId = tableId;
    }

    public TableName getName()
    {
        return tableName;
    }

    public Group getGroup()
    {
        return group;
    }

    public void setGroup(Group group)
    {
        this.group = group;
    }

    public Column getColumn(String columnName)
    {
        return columnMap.get(columnName.toLowerCase());
    }

    public Column getColumn(Integer position)
    {
        return getColumns().get(position);
    }

    public List<Column> getColumns()
    {
        ensureColumnsUpToDate();
        return removeInternalColumns(columns);
    }

    public List<Column> getColumnsIncludingInternal()
    {
        ensureColumnsUpToDate();
        return columns;
    }

    public Collection<TableIndex> getIndexes()
    {
        return unmodifiableIndexMap.values();
    }

    public TableIndex getIndex(String indexName)
    {
        return unmodifiableIndexMap.get(indexName.toLowerCase());
    }

    public final Collection<GroupIndex> getGroupIndexes() {
        return unmodifiableGroupIndexes;
    }

    public CharsetAndCollation getCharsetAndCollation()
    {
        return
            charsetAndCollation == null
            ? ais.getCharsetAndCollation()
            : charsetAndCollation;
    }

    public void setCharsetAndCollation(CharsetAndCollation charsetAndCollation)
    {
        this.charsetAndCollation = charsetAndCollation;
    }

    public void setCharset(String charset)
    {
        if (charset != null) {
            this.charsetAndCollation = CharsetAndCollation.intern(charset, getCharsetAndCollation().collation());
        }
    }

    public void setCollation(String collation)
    {
        if (collation != null) {
            this.charsetAndCollation = CharsetAndCollation.intern(getCharsetAndCollation().charset(), collation);
        }
    }

    public boolean isAISTable()
    {
        return tableName.getSchemaName().equals(TableName.AKIBAN_INFORMATION_SCHEMA);
    }

    protected void addColumn(Column column)
    {
        columnMap.put(column.getName().toLowerCase(), column);
        columnsStale = true;
    }

    protected void addIndex(TableIndex index)
    {
        indexMap.put(index.getIndexName().getName().toLowerCase(), index);
    }

    void clearIndexes() {
        indexMap.clear();
    }

    final void addGroupIndex(GroupIndex groupIndex) {
        groupIndexes.add(groupIndex);
    }

    final void removeGroupIndex(GroupIndex groupIndex) {
        groupIndexes.remove(groupIndex);
    }

    protected void dropColumns()
    {
        columnMap.clear();
        columnsStale = true;
    }

    protected Table()
    {
        // XXX: GWT requires empty constructor
        this.groupIndexes = new HashSet<GroupIndex>();
        this.unmodifiableGroupIndexes = Collections.unmodifiableCollection(groupIndexes);
        this.indexMap = new TreeMap<String, TableIndex>();
        this.unmodifiableIndexMap = Collections.unmodifiableMap(indexMap);
    }

    // For use by this package

    void setTableName(TableName tableName)
    {
        this.tableName = tableName;
    }

    public void removeIndexes(Collection<TableIndex> indexesToDrop) {
        indexMap.values().removeAll(indexesToDrop);
    }

    /**
     * <p>Our intended migration policy; the grouping algorithm must also take these values into account.</p>
     * <p/>
     * <p>The enums {@linkplain #KEEP_ENGINE} and {@linkplain #INCOMPATIBLE} have similar effects on grouping and
     * migration: tables marked with these values will not be included in any groups, and during migration, their
     * storage engine is not changed to AkibanDb. The difference between the two enums is that {@linkplain #KEEP_ENGINE}
     * is set by the user and can later be changed to {@linkplain #AKIBAN_STANDARD} or
     * {@linkplain @#AKIBAN_LOOKUP_TABLE}; on the other hand, {@linkplain #INCOMPATIBLE} is set during analysis and
     * signifies that migration will not work for this table. The user should not be able to set this flag, and if
     * this flag is set, the user should not be able to change it.</p>
     */
    public enum MigrationUsage
    {
        /**
         * Migrate this table to AkibanDb, grouping it as a standard user table. This is just a normal migration.
         */
        AKIBAN_STANDARD,
        /**
         * Migrate this table to AkibanDb, but as a lookup table. Lookup tables are grouped alone.
         */
        AKIBAN_LOOKUP_TABLE,
        /**
         * User wants to keep this table's engine as-is; don't migrate it to AkibanDb.
         */
        KEEP_ENGINE,
        /**
         * This table can't be migrated to AkibanDb.
         */
        INCOMPATIBLE;

        /**
         * Returns whether this usage requires an AkibanDB engine.
         *
         * @return whether this enum is one that requires AkibanDB
         */
        public boolean isAkiban()
        {
            return (this == AKIBAN_STANDARD) || (this == AKIBAN_LOOKUP_TABLE);
        }

        /**
         * <p>Returns whether this usage requires that the table participate in grouping.</p>
         * <p/>
         * <p>Tables participate in grouping if they're AkibanDB (see {@linkplain #isAkiban()} <em>and</em>
         * are not lookups.</p>
         *
         * @return
         */
        public boolean includeInGrouping()
        {
            return this == AKIBAN_STANDARD;
        }
    }

    /**
     * check if this table belongs to a frozen AIS, 
     * throw exception if ais is frozen 
     */
    void checkMutability() {
        ais.checkMutability();
    }
    /**
     * @deprecated - use AkibanInfomationSchema#validate() instead
     * @param out
     */
    public void checkIntegrity(List<String> out)
    {
        if (tableName == null) {
            out.add("table had null table name" + table);
        }
        for (Map.Entry<String, Column> entry : columnMap.entrySet()) {
            String name = entry.getKey();
            Column column = entry.getValue();
            if (column == null) {
                out.add("null column for name: " + name);
            } else if (name == null) {
                out.add("null name for column: " + column);
            } else if (!name.equals(column.getName())) {
                out.add("name mismatch, expected <" + name + "> for column " + column);
            }
        }
        if (!columnsStale) {
            for (Column column : columns) {
                if (column == null) {
                    out.add("null column in columns list");
                } else if (!columnMap.containsKey(column.getName())) {
                    out.add("columns not stale, but map didn't contain column: " + column.getName());
                }
            }
        }
        for (Map.Entry<String, TableIndex> entry : indexMap.entrySet()) {
            String name = entry.getKey();
            TableIndex index = entry.getValue();
            if (name == null) {
                out.add("null name for index: " + index);
            } else if (index == null) {
                out.add("null index for name: " + name);
            } else if (index.getTable() != this) {
                out.add("table's index.getTable() wasn't the table" + index + " <--> " + this);
            }
            if (index != null) {
                for (IndexColumn indexColumn : index.getColumns()) {
                    if (!index.equals(indexColumn.getIndex())) {
                        out.add("index's indexColumn.getIndex() wasn't index: " + indexColumn);
                    }
                    Column column = indexColumn.getColumn();
                    if (!columnMap.containsKey(column.getName())) {
                        out.add("index referenced a column not in the table: " + column);
                    }
                }
            }
        }
    }

    public String getEngine()
    {
        return engine;
    }

    Map<String, Column> getColumnMap()
    {
        return columnMap;
    }

    public void rowDef(Object rowDef)
    {
        assert rowDef.getClass().getName().equals("com.akiban.server.RowDef") : rowDef.getClass();
        this.rowDef = rowDef;
    }

    public Object rowDef()
    {
        return rowDef;
    }

    private void ensureColumnsUpToDate()
    {
        if (columnsStale) {
            columns.clear();
            columns.addAll(columnMap.values());
            Collections.sort(columns,
                             new Comparator<Column>()
                             {
                                 @Override
                                 public int compare(Column x, Column y)
                                 {
                                     return x.getPosition() - y.getPosition();
                                 }
                             });
            columnsStale = false;
        }
    }

    private static List<Column> removeInternalColumns(List<Column> columns)
    {
        List<Column> declaredColumns = new ArrayList<Column>(columns);
        for (Iterator<Column> iterator = declaredColumns.iterator(); iterator.hasNext();) {
            Column column = iterator.next();
            if (column.isAkibanPKColumn()) {
                iterator.remove();
            }
        }
        return declaredColumns;
    }

    // State

    protected AkibanInformationSchema ais;
    protected Group group;
    protected TableName tableName;
    private Integer tableId;
    private boolean columnsStale = true;
    private List<Column> columns = new ArrayList<Column>();
    private final Map<String, TableIndex> indexMap;
    private final Map<String, TableIndex> unmodifiableIndexMap;
    private Map<String, Column> columnMap = new TreeMap<String, Column>();
    private CharsetAndCollation charsetAndCollation;
    protected MigrationUsage migrationUsage = MigrationUsage.AKIBAN_STANDARD;
    protected String engine;

    private final Collection<GroupIndex> groupIndexes;
    private final Collection<GroupIndex> unmodifiableGroupIndexes;

    // It really is a RowDef, but declaring it that way creates trouble for AIS. We don't want to pull in
    // all the RowDef stuff and have it visible to GWT.
    private transient /*RowDef*/ Object rowDef;
}
