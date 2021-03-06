package com.EleNa;

import com.EleNa.repositories.EdgeRepository;
import com.EleNa.repositories.NodeRepository;
//import org.locationtech.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Coordinate;
import org.springframework.beans.factory.annotation.Autowired;

import org.dom4j.*;
import org.dom4j.io.SAXReader;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import com.EleNa.model.DataStructures.Node;
import com.EleNa.model.DataStructures.Edge;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OsmParser {
    private static NodeRepository nodeRepo;
    private static EdgeRepository edgeRepo;

    private static List<Node> nodes = new ArrayList<>();
    private static List<Edge> edges = new ArrayList<>();

    private static final int SQL_BATCH_INSERT = 500;
    private static final int REACH_NOTIFICATION = 50;

    private static int excludedWays = 0;
    public static int nodeCnt, wayCnt, edgeCnt;

    private static ArrayList<Long> nodeIds;

    @Autowired
    public OsmParser(NodeRepository nodeRepo, EdgeRepository edgeRepo) {
        this.nodeRepo = nodeRepo;
        this.edgeRepo = edgeRepo;

        this.nodeIds = new ArrayList<>();
    }

    public static String parseOSMFile(File osmFile){
        SAXReader reader = new SAXReader();
        try {
            Document document = reader.read( osmFile );
            System.out.println("Root element :" + document.getRootElement().getName());

            List<org.dom4j.Node> nodeNodes = document.selectNodes("/osm/node" );
            List<org.dom4j.Node> wayNodes = document.selectNodes("/osm/way" );

            System.out.println("Number of Node elements: " + nodeNodes.size());
            System.out.println("Number of Way elements: " + wayNodes.size() + "\n");

            parseWayNodes(wayNodes); // parse way nodes first without saving to exclude isolate nodes.
            parseNodeNodes(nodeNodes); // exclude isolated nodes
            // save nodes first since edges have fk referencing nodes

            saveBulkEdges();

            System.out.println("Excluded " + excludedWays + " ways");
        }
        catch(Exception e) {
            e.printStackTrace();
        }
        System.out.println("Finished");
        return "<h1>Imported node and way data</h1>";
    }

    public static void parseNodeNodes(List<org.dom4j.Node> nodeNodes) {
        int i = 0;
        for (org.dom4j.Node node : nodeNodes) {

            String nodeId = node.valueOf("@id");

            // Exclude nodes that are isolated and do not belong to a way.
            if(nodeIds.contains(Long.parseLong(nodeId))) {
                String lon = node.valueOf("@lon");
                String lat = node.valueOf("@lat");

                Coordinate coordinate = new Coordinate(Double.parseDouble(lon), Double.parseDouble(lat));
                Node osmNode = new Node(Long.parseLong(nodeId), coordinate);
                nodes.add(osmNode);
                i++;
                if (i % REACH_NOTIFICATION == 0) {
                    System.out.println("Reached " + i + " node elements");
                }
                if (i % SQL_BATCH_INSERT == 0) {
                    System.out.println("--- Reached " + i + " node elements and inserted into Nodes table");
                    saveNodes();
                }
            } else {
                System.out.println("Not including isolated node not in an edge: " + nodeId);
            }
        }
        saveNodes();
        OsmParser.nodeCnt = i;
        System.out.println("--- Finished inserting " + i + " nodes");
    }

    public static void saveNodes() {
        nodeRepo.saveAll(nodes);
        nodes.clear();
    }

    public static void parseWayNodes(List<org.dom4j.Node> wayNodes) {
        int wayCnt = 0;
        int ndCnt = 0;
        Edge prev = null;
        for(org.dom4j.Node wayNode : wayNodes) {
            List<org.dom4j.Node> wayChildrenNodes = wayNode.selectNodes(".//nd");
            List<org.dom4j.Node> wayChildrenTagNodes = wayNode.selectNodes(".//tag");

            boolean excludeCurrWay = excludeWay(wayChildrenTagNodes);
            if (excludeCurrWay) {
                continue;
            }
            System.out.println("--------> Including way: " + wayCnt);

            int ndIdx = 0;
            for(Iterator<org.dom4j.Node> iter = wayChildrenNodes.iterator(); iter.hasNext();) {
                org.dom4j.Node ndRefNode = iter.next();
                Long ndRef = Long.parseLong(ndRefNode.valueOf("@ref"));

                nodeIds.add(ndRef);

                if(ndIdx != 0) {
                    prev.setDest(ndRef);
                }

                if(iter.hasNext()) {
                    Edge edge = new Edge(ndRef);
                    edges.add(edge);
                    prev = edge;
                } else {
//                        System.out.println("NdIdx: " + ndIdx + ", last Way nd trigger don't create new Edge");
                }
                ndIdx++;
                ndCnt++;
            }
            wayCnt++;
            if(ndCnt % REACH_NOTIFICATION == 0) {
                System.out.println("Reached " + ndCnt + " nd elements");
            }
            if(ndCnt % SQL_BATCH_INSERT == 0) {
                System.out.println("--- Reached " + ndCnt + " way nd elements and inserted into Edges table");
            }
        }

        int total_edges = ndCnt - wayCnt;
        System.out.println("--- There are " + total_edges + " edges for " + wayCnt + " ways");
        OsmParser.wayCnt = wayCnt;
        OsmParser.edgeCnt = total_edges;
    }

    public static void saveBulkEdges() {
        System.out.println("Edges size: " + edges.size());
        int edgeSize = edges.size();
        List<Edge> insertEdges = new ArrayList<>();

        for (int i = 0; i < edgeSize; i ++) {
            insertEdges.add(edges.get(i));
            if (i != 0 && i % SQL_BATCH_INSERT == 0) {
                System.out.println("--- Reached " + i + " edges. Inserting 500 edges");
                saveEdges(insertEdges);
                insertEdges.clear();
            }
        }
        System.out.println("Saving rest of edges " + insertEdges.size());
        saveEdges(insertEdges);
    }

    public static void saveEdges(List<Edge> edgesBundle) {
        edgeRepo.saveAll(edgesBundle);
    }

    public static boolean excludeWay(List<org.dom4j.Node> wayChildrenTagNodes) {
        List<String> excludedHighwayValues = Arrays.asList(new String[] {"motorway", "trunk"});
        boolean hasGoodWay = true;
        for (org.dom4j.Node node: wayChildrenTagNodes) {
            String key = node.valueOf("@k");
            String value = node.valueOf("@v");

            if (key.equals("highway") && !excludedHighwayValues.contains(value)) {
                System.out.println("---> This way will be included: " + key + "," + value);
                hasGoodWay = false;
            }
            else if(excludedHighwayValues.contains(value)){
                System.out.println("--> Not anymore. Excluding a highway value: " + value);
                return true; // could be service then trunk so exclude
            }

        }
        return hasGoodWay;
    }

    @GetMapping("/importData")
    public String importData(@RequestParam String path) {
        File osmFile = new File(path);
        return parseOSMFile(osmFile);
    }

    public static void main(String[] args) {
        String filePath = "C:\\Users\\T450-180519\\Documents\\Coding_Projects\\520-Project\\src\\main\\resources\\map_small_test.osm";
        File osmFile = new File(filePath);
        parseOSMFile(osmFile);
    }

}
