# MyCompRequestDumpingHandler

Code for dumping Wildfly requests to server.log if request response duration takes more than x seconds.

standalone.xml configuration:

        <subsystem xmlns="urn:jboss:domain:undertow:3.1">
            <buffer-cache name="default"/>
            <server name="default-server">
                <http-listener name="default" socket-binding="http" redirect-socket="https" enable-http2="true"/>
                <https-listener name="https" socket-binding="https" security-realm="ApplicationRealm" enable-http2="true"/>
                <host name="default-host" alias="localhost">
                    <location name="/" handler="welcome-content"/>
                    <filter-ref name="server-header"/>
                    <filter-ref name="x-powered-by-header"/>
                    *<filter-ref name="request-dumper"/>*
                </host>
            </server>
            <servlet-container name="default">
                <jsp-config/>
                <websockets/>
            </servlet-container>
            <handlers>
                <file name="welcome-content" path="${jboss.home.dir}/welcome-content"/>
            </handlers>
            <filters>
                <response-header name="server-header" header-name="Server" header-value="WildFly/10"/>
                <response-header name="x-powered-by-header" header-name="X-Powered-By" header-value="Undertow/1"/>
                *<filter name="request-dumper" class-name="si.mycomp.requestDumpingHandler.DfsRequestDumpingHandler" module="si.mycomp.requestDumpingHandler">
                    <param name="timeLimit" value="0.1"/>
                </filter>*
            </filters>
        </subsystem>
