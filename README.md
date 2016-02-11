#Randy's Doradus
This is a fork of the dell-oss/Doradus project originally located at: [https://github.com/dell-oss/Doradus](https://github.com/dell-oss/Doradus). This version has changes that are intended to simplify Doradus for certain cases. Compared to the dell-oss version, here are the main changes I've made so far:

- All "tenant" code has been removed, making Doradus a single-tenant database. The hetereogeneous multi-tenant functionality was highly specialized and added lots of complexity. A better way to make implement multi-tenancy is to place a "tenant manager" service in front of multiple, independent Doradus processes. Maybe I'll create one of those some day.

- The doradus-jetty module has been deleted, and the embedded Jetty server has been moved into the doradus-server module. This makes it easier to run Doradus with its REST interface, which is the normal case. However, you can still suppress the interal REST service by removing com.dell.doradus.service.rest.RESTService from doradus.yaml. Alternate web servers can also still be created and used.

- The JMX (MBean) service has been removed from doradus-server, and corresponding classes in doradus-common have also been removed. This interface was not really used and some features didn't work.

- A minor change was made to the OLAPMonoService to suppress the "_shard" field from object queriers, since the value is alwaus "_mono".

See the dell-oss project referenced above for general notes and links about Doradus. Eventually, I'll update the docs in this fork to match all the changes I've been making. (If you're reading this and would like to see updated docs, feel free to bug me about it!)
