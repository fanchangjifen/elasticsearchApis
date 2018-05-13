package com.gilbert.elasticsearch.conf;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.xpack.client.PreBuiltXPackTransportClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticsearchConfig{
	
	private static final Logger logger = LoggerFactory.getLogger(ElasticsearchConfig.class); 
	/**
	 * 集群名称
	 */
	@Value("${spring.elasticsearch.cluster-name}")
	private String clusterName;
	/**
	 * 集群节点
	 */
	@Value("${spring.elasticsearch.cluster-nodes}")
	private String clusterNodes;
	/**
	 * x-pack 用户名
	 */
	@Value("${spring.elasticsearch.username}")
	private String username;
	/**
	 * x-pack 密码
	 */
	@Value("${spring.elasticsearch.password}")
	private String password;
	
	private int ConnectTimeout = 5000;
	
	private int SocketTimeout = 60000;
	
	private int MaxRetryTimeoutMillis = 60000;
	
	private int IoThreadCount = 1;
	/**
	 * The time to wait for a ping response from a node. Defaults to 5s.
	 */
	private String clientPingTimeout = "5s";
	/**
	 * How often to sample / ping the nodes listed and connected. Defaults to 5s.
	 */
    private String clientNodesSamplerInterval = "5s";
	
	@Bean
	public Client client() throws UnknownHostException {
		//设置集群名称
		Settings settings = Settings.builder()
		        .put("cluster.name", clusterName)
		        .put("xpack.security.user", username+":"+password)
		        //Set to true to ignore cluster name validation of connected nodes. 
                .put("client.transport.ignore_cluster_name", Boolean.FALSE)
                .put("client.transport.ping_timeout", clientPingTimeout)
                .put("client.transport.nodes_sampler_interval", clientNodesSamplerInterval)
		        .put("client.transport.sniff", Boolean.TRUE).build();
		//创建客户端
		TransportClient client = new PreBuiltXPackTransportClient(settings);
		if(null!=clusterNodes){
			String[] nodes = clusterNodes.split(",");
			for(String node : nodes){
				String host = node.split(":")[0];
				Integer port = Integer.valueOf(node.split(":")[1]);
				client.addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName(host), port));
			}
		}
		return client;
	}
	
	@Bean
	public RestClient restClient() throws IOException{
		logger.info("Init elasticsearch cluster {} start ...",clusterName);
		
		String[] nodes = clusterNodes.split(",");
		HttpHost[] hosts = new HttpHost[nodes.length];
		for(int i=0,j=nodes.length;i<j;i++){
			String host = nodes[i].split(":")[0];
			Integer port = 9200;
			hosts[i] = new HttpHost(host,port);
		}
		
		final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
		credentialsProvider.setCredentials(AuthScope.ANY,
		        new UsernamePasswordCredentials(username, password));
		
		RestClientBuilder builder = RestClient.builder(hosts)
				.setRequestConfigCallback(new RestClientBuilder.RequestConfigCallback() {
		            @Override
		            public RequestConfig.Builder customizeRequestConfig(RequestConfig.Builder requestConfigBuilder) {
		                return requestConfigBuilder.setConnectTimeout(ConnectTimeout)
		                        .setSocketTimeout(SocketTimeout);
		            }
		        })
		        .setMaxRetryTimeoutMillis(MaxRetryTimeoutMillis)
				.setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
		            @Override
		            public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
		                return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider)
		                		.setDefaultIOReactorConfig(
		                                IOReactorConfig.custom().setIoThreadCount(IoThreadCount).build());
		            }
		        });
		return builder.build();
	}
	
	@Bean
	public RestHighLevelClient restHighLevelClient() throws IOException{
		return new RestHighLevelClient(restClient());
	}
	
}