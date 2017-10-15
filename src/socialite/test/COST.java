package socialite.test;

import org.apache.commons.lang3.time.StopWatch;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroup;
import socialite.engine.ClientEngine;
import socialite.engine.Config;
import socialite.engine.LocalEngine;
import socialite.util.MySTGroupFile;

import java.io.FileNotFoundException;

public class COST {
    public static final int SRC_NODE = 0;

    //dateset        iter
    //1M             31
    //5M             36
    //10M            38
    //0              1                  2             3            4            5
    //single     thread-num          node-num       basic         assb        iter
    //dist         node-num           basic        assb            iter
    public static void main(String[] args) throws FileNotFoundException {
        STGroup stg = new MySTGroupFile(COST.class.getResource("COST.stg"),
                "UTF-8", '<', '>');
        stg.load();
        if(args[0].equals("single")) {
            LocalEngine en = new LocalEngine(Config.par(Integer.parseInt(args[1])));//config
            int nodeCount = Integer.parseInt(args[2]);
            ST st = stg.getInstanceOf("Init");
            st.add("N", nodeCount);
            st.add("BASIC_PATH", args[3]);
            st.add("ASSB_PATH", args[4]);
            st.add("SPLITTER", "\t");
            String init = st.render();
            System.out.println(init);
            en.run(init);
            st = stg.getInstanceOf("Iter");
            StopWatch stopWatch = new StopWatch();
            stopWatch.start();
            for (int i = 0; i < Integer.parseInt(args[5]); i++) {
                st.add("i", i);
                String iterCode = st.render();
                st.remove("i");
                en.run(iterCode);
                System.out.println("iter " + i);
            }
            stopWatch.stop();
            System.out.println("elapsed " + stopWatch.getTime());
            en.shutdown();
        }else if(args[0].equals("dist")){
            ClientEngine en = new ClientEngine();
            int nodeCount = Integer.parseInt(args[1]);
            ST st = stg.getInstanceOf("Init");
            st.add("N", nodeCount);
            st.add("BASIC_PATH", args[2]);
            st.add("ASSB_PATH", args[3]);
            st.add("SPLITTER", "\t");
            String init = st.render();
            System.out.println(init);
            en.run(init);
            st = stg.getInstanceOf("Iter");
            StopWatch stopWatch = new StopWatch();
            stopWatch.start();
            for (int i = 0; i < Integer.parseInt(args[4]); i++) {
                st.add("i", i);
                String iterCode = st.render();
                st.remove("i");
                en.run(iterCode);
                System.out.println("iter " + i);
            }
            stopWatch.stop();
            System.out.println("elapsed " + stopWatch.getTime());
            en.shutdown();
        }
    }
}
