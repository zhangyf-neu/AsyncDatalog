package socialite.test;

import org.apache.commons.lang3.time.StopWatch;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;
import socialite.dist.master.MasterNode;
import socialite.engine.ClientEngine;
import socialite.engine.Config;
import socialite.engine.LocalEngine;
import socialite.tables.QueryVisitor;
import socialite.tables.Tuple;
import socialite.util.MySTGroupFile;

import java.io.FileNotFoundException;

public class SSSP {
    public static final int SRC_NODE = 0;
    private static final Log L = LogFactory.getLog(SSSP.class);

    //dateset        node         iter   distSum
    //livejournal    4847571      16     2243012525
    //google         875713       26     590940903
    //berkstan       685230       356
    //0            1                2              3
    //single      thread-num      node-num        edge-path
    //dist         node-num         edge-path     iter
    public static void main(String[] args) throws FileNotFoundException, InterruptedException {
        if (args[0].equals("single")) {
            STGroup stg = new MySTGroupFile(SSSP.class.getResource("SSSP.stg"),
                    "UTF-8", '<', '>');
            stg.load();
            LocalEngine en = new LocalEngine(Config.par(Integer.parseInt(args[1])));
            int nodeCount = Integer.parseInt(args[2]);
            ST st = stg.getInstanceOf("Init");
            st.add("N", nodeCount);
            st.add("PATH", args[3]);
            st.add("SPLITTER", "\t");
            String init = st.render();
            System.out.println(init);

            StopWatch stopWatch = new StopWatch();
            stopWatch.start();
            en.run(init);
            stopWatch.stop();
            L.info("elapsed " + stopWatch.getTime());
            en.shutdown();
        } else if (args[0].equals("dist")) {
            STGroup stg = new MySTGroupFile(SSSP.class.getResource("SSSP1.stg"),
                    "UTF-8", '<', '>');
            stg.load();
            MasterNode.startMasterNode();
            while (MasterNode.getInstance().getQueryListener().getEngine() == null)//waiting workers online
                Thread.sleep(100);
            ClientEngine en = new ClientEngine();
            int nodeCount = Integer.parseInt(args[1]);
            int iter = Integer.parseInt(args[3]);
            ST st = stg.getInstanceOf("Init");
            st.add("N", nodeCount);
            st.add("PATH", args[2]);
            st.add("SPLITTER", "\t");
            String init = st.render();
            System.out.println(init);
            en.run(init);
            st = stg.getInstanceOf("Iter");
            st.add("N", nodeCount);
            st.add("SRC", SRC_NODE + "");
            long start = System.currentTimeMillis();
            for (int i = 0; i < iter; i++) {
                st.add("i", i);
                String iterCode = st.render();
                st.remove("i");
                en.run(iterCode);
            }
            System.out.println("recursive statement:" + (System.currentTimeMillis() - start));
            en.shutdown();
        }
    }
}
