#What's Happending with Doradus?
The Doradus database engine was started at Quest Software to support a new analytics application. Doradus successfully solved storage and query problems unique to this application, resulting in many internal releases. In 2012, Dell acquired Quest Software, and the team got permission to make Doradus an open source project on Github in 2014. Starting in 2015 and completing in 2016, Dell decided to sell most of its software divisions, being Quest Software back to life. However, the team was not able to sustain the open source version, and a private version was forked for internal use. The original Doradus open source project is available here: [https://github.com/quest-oss/Doradus](https://github.com/quest-oss/Doradus).
When I left Dell in October, 2016, I forked the master project to create the version here. My goals are to ensure that the project remains available and to release a series of changes to keep the project going. Most of my changes are simplifications, removing features needed by the original application but that add complexity while providing little value to the general public. This is a background, just-for-fun project.

#Randy's Doradus
The changes I’ve made are summarized below. 

- The application "key" property has been eliminated. Application keys served as a saftey mechanism to prevent accidental modification or deletion of the wrong application. But this paranoid got in the way more than it helped.

- All support for the legacy YAML file format has been removed. The "module" style format is now required. Among other things, this means you can set/override module parameters using either of the following command-line argument styles:

```
	-DBService '{dbservice=com.dell.doradus.service.db.fs.FsService}' -FsService '{db-path=/Users/rguck/doradus-data}'
	-DBService.dbservice com.dell.doradus.service.db.fs.FsService -FsService.db-path /Users/rguck/doradus-data
```

- All "tenant" code has been removed, making Doradus a single-tenant database. The hetereogeneous multi-tenant functionality was highly specialized and added lots of complexity. A better way to make implement multi-tenancy is to place a "tenant manager" service in front of multiple, independent Doradus processes. Maybe I'll create one of those some day.

- The doradus-jetty module has been deleted, and the embedded Jetty server has been moved into the doradus-server module. This makes it easier to run Doradus with its REST interface, which is the normal case. However, you can still suppress the interal REST service by removing com.dell.doradus.service.rest.RESTService from doradus.yaml. Alternate web servers can also still be created and used.

- The JMX (MBean) service has been removed from doradus-server, and corresponding classes in doradus-common have also been removed. This interface was not really used and some features didn't work.

- A minor change was made to the OLAPMonoService to suppress the "\_shard" field from object queries, since the value is alwaus "\_mono".

Eventually, I'll update the docs in this fork to match all the changes I've been making. I hope to create new, simpler documentation as well. (If you're reading this and would like to see updated docs, feel free to bug me about it!)
