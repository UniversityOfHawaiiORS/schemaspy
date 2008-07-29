package net.sourceforge.schemaspy.view;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import net.sourceforge.schemaspy.*;
import net.sourceforge.schemaspy.model.*;
import net.sourceforge.schemaspy.util.*;
import net.sourceforge.schemaspy.view.DotNode.DotNodeConfig;

/**
 * Format table data into .dot format to feed to GraphVis' dot program.
 *
 * @author John Currier
 */
public class DotFormatter {
    private static DotFormatter instance = new DotFormatter();
    private final int fontSize = Config.getInstance().getFontSize();

    /**
     * Singleton - prevent creation
     */
    private DotFormatter() {
    }

    public static DotFormatter getInstance() {
        return instance;
    }

    /**
     * Write all relationships (including implied) associated with the given table
     */
    public void writeRealRelationships(Table table, boolean twoDegreesOfSeparation, WriteStats stats, LineWriter dot) throws IOException {
        boolean origImplied = stats.setIncludeImplied(false);
        writeRelationships(table, twoDegreesOfSeparation, stats, dot);
        stats.setIncludeImplied(origImplied);
    }

    /**
     * Write implied relationships associated with the given table
     */
    public void writeAllRelationships(Table table, boolean twoDegreesOfSeparation, WriteStats stats, LineWriter dot) throws IOException {
        boolean origImplied = stats.setIncludeImplied(true);
        writeRelationships(table, twoDegreesOfSeparation, stats, dot);
        stats.setIncludeImplied(origImplied);
    }

    /**
     * Write relationships associated with the given table
     */
    private void writeRelationships(Table table, boolean twoDegreesOfSeparation, WriteStats stats, LineWriter dot) throws IOException {
        Pattern regex = getRegexWithoutTable(table, stats);
        Pattern originalRegex = stats.setExclusionPattern(regex);
        Set<Table> tablesWritten = new HashSet<Table>();

        DotConnectorFinder finder = DotConnectorFinder.getInstance();

        String graphName = stats.includeImplied() ? "impliedTwoDegreesRelationshipsGraph" : (twoDegreesOfSeparation ? "twoDegreesRelationshipsGraph" : "oneDegreeRelationshipsGraph");
        writeHeader(graphName, true, dot);

        Set<Table> relatedTables = getImmediateRelatives(table, stats);

        Set<DotConnector> connectors = new TreeSet<DotConnector>(finder.getRelatedConnectors(table, stats));
        tablesWritten.add(table);

        Map<Table, DotNode> nodes = new TreeMap<Table, DotNode>();

        // write immediate relatives first
        for (Table relatedTable : relatedTables) {
            if (!tablesWritten.add(relatedTable))
                continue; // already written

            nodes.put(relatedTable, new DotNode(relatedTable, true, ""));
            connectors.addAll(finder.getRelatedConnectors(relatedTable, table, stats));
        }

        // connect the edges that go directly to the target table
        // so they go to the target table's type column instead
        for (DotConnector connector : connectors) {
            if (connector.pointsTo(table))
                connector.connectToParentDetails();
        }

        Set<Table> allCousins = new HashSet<Table>();
        Set<DotConnector> allCousinConnectors = new TreeSet<DotConnector>();

        // next write 'cousins' (2nd degree of separation)
        if (twoDegreesOfSeparation) {
            for (Table relatedTable : relatedTables) {
                Set<Table> cousins = getImmediateRelatives(relatedTable, stats);

                for (Table cousin : cousins) {
                    if (!tablesWritten.add(cousin))
                        continue; // already written

                    allCousinConnectors.addAll(finder.getRelatedConnectors(cousin, relatedTable, stats));
                    nodes.put(cousin, new DotNode(cousin, false, ""));
                }

                allCousins.addAll(cousins);
            }
        }

        markExcludedColumns(nodes, stats.getExcludedColumns());

        // now directly connect the loose ends to the title of the
        // 2nd degree of separation tables
        for (DotConnector connector : allCousinConnectors) {
            if (allCousins.contains(connector.getParentTable()))
                connector.connectToParentTitle();
            if (allCousins.contains(connector.getChildTable()))
                connector.connectToChildTitle();
        }

        // include the table itself
        nodes.put(table, new DotNode(table, ""));

        connectors.addAll(allCousinConnectors);
        for (DotConnector connector : connectors) {
            if (connector.isImplied()) {
                DotNode node = nodes.get(connector.getParentTable());
                if (node != null)
                    node.setShowImplied(true);
                node = nodes.get(connector.getChildTable());
                if (node != null)
                    node.setShowImplied(true);
            }
            dot.writeln(connector.toString());
        }

        for (DotNode node : nodes.values()) {
            dot.writeln(node.toString());
            stats.wroteTable(node.getTable());
        }

        dot.writeln("}");
        stats.setExclusionPattern(originalRegex);
    }

    private Pattern getRegexWithoutTable(Table table, WriteStats stats) {
        Set<String> pieces = new HashSet<String>();
        List<String> regexes = Arrays.asList(stats.getExclusionPattern().pattern().split("\\)\\|\\("));
        for (int i = 0; i < regexes.size(); ++i) {
            String regex = regexes.get(i).toString();
            if (!regex.startsWith("("))
                regex = "(" + regex;
            if (!regex.endsWith(")"))
                regex = regex + ")";
            pieces.add(regex);
        }

        // now removed the pieces that match some of the columns of this table
        Iterator<String> iter = pieces.iterator();
        while (iter.hasNext()) {
            String regex = iter.next();
            for (TableColumn column : table.getColumns()) {
                Pattern columnPattern = Pattern.compile(regex);
                if (column.matches(columnPattern)) {
                    iter.remove();
                    break;
                }
            }
        }

        StringBuffer pattern = new StringBuffer();
        for (String piece : pieces) {
            if (pattern.length() > 0)
                pattern.append("|");
            pattern.append(piece);
        }

        return Pattern.compile(pattern.toString());
    }

    private Set<Table> getImmediateRelatives(Table table, WriteStats stats) {
        Set<TableColumn> relatedColumns = new HashSet<TableColumn>();
        boolean foundImplied = false;
        Iterator iter = table.getColumns().iterator();
        while (iter.hasNext()) {
            TableColumn column = (TableColumn)iter.next();
            if (DotConnector.isExcluded(column, stats)) {
                stats.addExcludedColumn(column);
                continue;
            }
            Iterator childIter = column.getChildren().iterator();
            while (childIter.hasNext()) {
                TableColumn childColumn = (TableColumn)childIter.next();
                if (DotConnector.isExcluded(childColumn, stats)) {
                    stats.addExcludedColumn(childColumn);
                    continue;
                }
                boolean implied = column.getChildConstraint(childColumn).isImplied();
                foundImplied |= implied;
                if (!implied || stats.includeImplied())
                    relatedColumns.add(childColumn);
            }
            Iterator parentIter = column.getParents().iterator();
            while (parentIter.hasNext()) {
                TableColumn parentColumn = (TableColumn)parentIter.next();
                if (DotConnector.isExcluded(parentColumn, stats)) {
                    stats.addExcludedColumn(parentColumn);
                    continue;
                }
                boolean implied = column.getParentConstraint(parentColumn).isImplied();
                foundImplied |= implied;
                if (!implied || stats.includeImplied())
                    relatedColumns.add(parentColumn);
            }
        }

        Set<Table> relatedTables = new HashSet<Table>();
        for (TableColumn column : relatedColumns)
            relatedTables.add(column.getTable());

        relatedTables.remove(table);
        stats.setWroteImplied(foundImplied);
        return relatedTables;
    }

    private void writeHeader(String graphName, boolean showLabel, LineWriter dot) throws IOException {
        dot.writeln("// dot " + Dot.getInstance().getVersion() + " on " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        dot.writeln("digraph \"" + graphName + "\" {");
        dot.writeln("  graph [");
        boolean rankdirbug = Config.getInstance().isRankDirBugEnabled();
        if (!rankdirbug)
            dot.writeln("    rankdir=\"RL\"");
        dot.writeln("    bgcolor=\"" + StyleSheet.getInstance().getBodyBackground() + "\"");
        if (showLabel) {
            if (rankdirbug)
                dot.writeln("    label=\"\\nLayout is significantly better without '-rankdirbug' option\"");
            else
                dot.writeln("    label=\"\\nGenerated by SchemaSpy\"");
            dot.writeln("    labeljust=\"l\"");
        }
        dot.writeln("    nodesep=\"0.18\"");
        dot.writeln("    ranksep=\"0.46\"");
        dot.writeln("    fontname=\"" + Config.getInstance().getFont() + "\"");
        dot.writeln("    fontsize=\"" + fontSize + "\"");
        dot.writeln("  ];");
        dot.writeln("  node [");
        dot.writeln("    fontname=\"" + Config.getInstance().getFont() + "\"");
        dot.writeln("    fontsize=\"" + fontSize + "\"");
        dot.writeln("    shape=\"plaintext\"");
        dot.writeln("  ];");
        dot.writeln("  edge [");
        dot.writeln("    arrowsize=\"0.8\"");
        dot.writeln("  ];");
}

    public void writeRealRelationships(Database db, Collection<Table> tables, boolean compact, boolean showColumns, WriteStats stats, LineWriter dot) throws IOException {
        boolean oldImplied = stats.setIncludeImplied(false);
        writeRelationships(db, tables, compact, showColumns, stats, dot);
        stats.setIncludeImplied(oldImplied);
    }

    public void writeAllRelationships(Database db, Collection<Table> tables, boolean compact, boolean showColumns, WriteStats stats, LineWriter dot) throws IOException {
        boolean oldImplied = stats.setIncludeImplied(true);
        writeRelationships(db, tables, compact, showColumns, stats, dot);
        stats.setIncludeImplied(oldImplied);
    }

    private void writeRelationships(Database db, Collection<Table> tables, boolean compact, boolean showColumns, WriteStats stats, LineWriter dot) throws IOException {
        DotConnectorFinder finder = DotConnectorFinder.getInstance();
        DotNodeConfig nodeConfig = showColumns ? new DotNodeConfig(!compact, false) : new DotNodeConfig();
        
        String graphName;
        if (stats.includeImplied()) {
            if (compact)
                graphName = "compactImpliedRelationshipsGraph";
            else
                graphName = "largeImpliedRelationshipsGraph";
        } else {
            if (compact)
                graphName = "compactRelationshipsGraph";
            else
                graphName = "largeRelationshipsGraph";
        }
        writeHeader(graphName, true, dot);

        Map<Table, DotNode> nodes = new TreeMap<Table, DotNode>();

        for (Table table : tables) {
            if (!table.isOrphan(stats.includeImplied())) {
                nodes.put(table, new DotNode(table, "tables/", nodeConfig));
            }
        }

        for (Table table : db.getRemoteTables()) {
            nodes.put(table, new DotNode(table, "../" + table.getSchema() + "/tables", nodeConfig));
        }

        Set<DotConnector> connectors = new TreeSet<DotConnector>();

        for (DotNode node : nodes.values()) {
            connectors.addAll(finder.getRelatedConnectors(node.getTable(), stats));
        }

        markExcludedColumns(nodes, stats.getExcludedColumns());

        for (DotNode node : nodes.values()) {
            Table table = node.getTable();

            dot.writeln(node.toString());
            stats.wroteTable(table);
            if (stats.includeImplied() && table.isOrphan(!stats.includeImplied())) {
                stats.setWroteImplied(true);
            }
        }

        for (DotConnector connector : connectors) {
            dot.writeln(connector.toString());
        }

        dot.writeln("}");
    }

    private void markExcludedColumns(Map<Table, DotNode> nodes, Set<TableColumn> excludedColumns) {
        for (TableColumn column : excludedColumns) {
            DotNode node = nodes.get(column.getTable());
            if (node != null) {
                node.excludeColumn(column);
            }
        }
    }

    public void writeOrphan(Table table, LineWriter dot) throws IOException {
        writeHeader(table.getName(), false, dot);
        dot.writeln(new DotNode(table, true, "tables/").toString());
        dot.writeln("}");
    }
}
