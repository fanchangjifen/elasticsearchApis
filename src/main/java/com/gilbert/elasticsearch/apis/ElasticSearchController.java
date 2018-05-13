package com.gilbert.elasticsearch.apis;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.search.suggest.Suggest;
import org.elasticsearch.search.suggest.SuggestBuilder;
import org.elasticsearch.search.suggest.SuggestBuilders;
import org.elasticsearch.search.suggest.SuggestionBuilder;
import org.elasticsearch.search.suggest.completion.CompletionSuggestion;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/search")
public class ElasticSearchController {
	
	public Logger logger = LoggerFactory.getLogger(ElasticSearchController.class);
	
	@Autowired
	private Client client;
	
	private static final String goodsIndex = "goods";
	private static final String apiLogIndex = "apilog";
	
	//分页查询接口及高亮
	@SuppressWarnings("deprecation")
	@PostMapping("/list")
	public JSONObject getGoodsPageList(@RequestBody JSONObject obj){
		JSONArray array = new JSONArray();
		String name = "name";
		String describe = "describe";
		try {
			String key = obj.getString("name");
			Integer page = obj.getInteger("page");
			Integer limit = obj.getInteger("limit");
			String price = obj.getString("price");
			String sort = obj.getString("sort");
			String order = obj.getString("order");
			
			SearchRequestBuilder searchRequestBuilder = client
					.prepareSearch(goodsIndex);
			searchRequestBuilder.setSearchType(SearchType.DFS_QUERY_THEN_FETCH);
			//分页
			searchRequestBuilder.setFrom((page-1)*limit).setSize(limit);
			BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
			//查询全部
			queryBuilder.must(QueryBuilders.matchAllQuery());
			//查询状态为7
			queryBuilder.must(QueryBuilders.termQuery("status", 7));
			//查询价格区间
			if(StringUtils.isNotBlank(price)){
				RangeQueryBuilder rangeQuery = new RangeQueryBuilder("price");
				String[] prices = StringUtils.split(price, "-");
				if(prices.length>1&&StringUtils.isNotBlank(prices[0])&&StringUtils.isNotBlank(prices[1])){
					rangeQuery.gte(prices[0]).lte(prices[1]);
				}else{
					rangeQuery.gte(prices[0]);
				}
				queryBuilder.must(rangeQuery);
			}
			//关键字查询
			if (StringUtils.isNotBlank(key)) {
				//设置搜索关键词
				queryBuilder.must(QueryBuilders.queryStringQuery(key).field(name).field(describe));
				searchRequestBuilder.setExplain(true);
				//设置高亮显示
				HighlightBuilder highlightBuilder = new HighlightBuilder().field(name).field(describe).requireFieldMatch(false);
				highlightBuilder.preTags("<span style=\"color:red\">");
				highlightBuilder.postTags("</span>");
				searchRequestBuilder.highlighter(highlightBuilder);
			}
			searchRequestBuilder.setQuery(queryBuilder);
			//结果排序
			if(StringUtils.isNotBlank(sort)&&StringUtils.isNotBlank(order)){
				searchRequestBuilder.addSort(sort, SortOrder.fromString(order));
			}else if(StringUtils.isNotBlank(order)){
				searchRequestBuilder.addSort("_score", SortOrder.fromString(order));
			}
			SearchResponse res = searchRequestBuilder.execute().actionGet();
			ObjectMapper mapper = new ObjectMapper();
			JSONObject json = new JSONObject();
			SearchHits searchHits = res.getHits();
			long count = searchHits.getTotalHits();
			SearchHit[] hits = searchHits.getHits();
			for (SearchHit hit:hits) {
				String jsonStr = hit.getSourceAsString();
				json = mapper.readValue(jsonStr, JSONObject.class);
				Map<String, HighlightField> result = hit.highlightFields();
				HighlightField nameField = result.get(name);
				HighlightField remarkField = result.get(describe);
				Text[] texts = null;
				String nameStr = "";
				String remark = "";
				if (null != nameField) {
					texts = nameField.fragments();
					for (Text text : texts) {
						nameStr += text;
					}
					json.put(name, nameStr);
				}
				if (null != remarkField) {
					texts = remarkField.fragments();
					for (Text text : texts) {
						remark += text;
					}
					json.put(describe, remark);
				}
				array.add(json);
			}
			//封装返回结果
			return new JSONObject();
		} catch (Exception e) {
			logger.error(e.getMessage());
			return new JSONObject();
		}
	}
	
	//搜索推荐
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@GetMapping("/suggester")
	public JSONObject getGoodsSuggester(@RequestParam("key") String key){
		try{
			SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
			SuggestionBuilder termSuggestionBuilder =
			    SuggestBuilders.completionSuggestion("goodsSuggest").text(key); 
			SuggestBuilder suggestBuilder = new SuggestBuilder();
			suggestBuilder.addSuggestion("suggest", termSuggestionBuilder); 
			searchSourceBuilder.suggest(suggestBuilder); 
			searchSourceBuilder.from(0); 
			searchSourceBuilder.size(5);
			SearchRequest searchRequest = new SearchRequest();
			searchRequest.indices(goodsIndex);
			searchRequest.source(searchSourceBuilder);
			ActionFuture<SearchResponse> future = client.search(searchRequest);
			SearchResponse res = future.actionGet();
			Suggest suggest = res.getSuggest();
			CompletionSuggestion complationSuggestion = suggest.getSuggestion("suggest"); 
			Set set = new HashSet();
			List<String> list = new ArrayList<String>();
			for (CompletionSuggestion.Entry entry : complationSuggestion.getEntries()) { 
			    for (CompletionSuggestion.Entry.Option option : entry) { 
			        String suggestText = option.getText().string();
			        if(set.add(suggestText)){
			        	list.add(suggestText);
			        }
			    }
			}
			return new JSONObject();
		}catch(Exception e){
			e.printStackTrace();
			return new JSONObject();
		}
	}
	
	//时间区间聚合
	@PostMapping("/dateHistogramAggregation")
	public JSONObject getApiLogDateHistogramAggregation(@RequestBody JSONObject obj){
		Date startTime = obj.getDate("startTime");
		Date endTime = obj.getDate("endTime");
		String type = obj.getString("type");
		String uid = obj.getString("uid");
		try{
			SearchRequestBuilder  searchRequestBuilder = client.prepareSearch(apiLogIndex).setTypes(apiLogIndex);
			//构建bool查询
			BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();
			//查询全部
			queryBuilder.must(QueryBuilders.matchAllQuery());
			//查询当前用户
			if(StringUtils.isNotBlank(uid)){
				queryBuilder.must(QueryBuilders.termQuery("creator", uid));
			}
			//根据时间段查询
			if(null!=startTime||null!= endTime){
				RangeQueryBuilder rangeQuery = new RangeQueryBuilder("createTime");
				if(null!=startTime){
					rangeQuery.gte(startTime.getTime());
				}
				if(null!=endTime){
					rangeQuery.lte(endTime.getTime());
				}
				queryBuilder.must(rangeQuery);
			}
			searchRequestBuilder.setQuery(queryBuilder);
			DateHistogramAggregationBuilder dateAgg = AggregationBuilders.dateHistogram("dateAgg").field("createTime");
			//处理UTC时间
			dateAgg.timeZone(DateTimeZone.forOffsetHours(8));
			dateAgg.minDocCount(0);
			//格式化日期,统计时间段内的请求量
			if("month".equals(type)){
				dateAgg.format("yyyy-MM");
				dateAgg.dateHistogramInterval(DateHistogramInterval.MONTH);
			}else if("day".equals(type)){
				dateAgg.format("yyyy-MM-dd");
				dateAgg.dateHistogramInterval(DateHistogramInterval.DAY);
			}else{
				dateAgg.format("yyyy-MM-dd HH");
				dateAgg.dateHistogramInterval(DateHistogramInterval.HOUR);
			}
			TermsAggregationBuilder codeAgg = AggregationBuilders.terms("codeAgg").field("code");
			searchRequestBuilder.addAggregation(dateAgg.subAggregation(codeAgg));
			JSONArray array = new JSONArray();
			Histogram hs = searchRequestBuilder.get().getAggregations().get("dateAgg");
			for (Histogram.Bucket entry : hs.getBuckets()) {
				JSONObject json = new JSONObject();
				long successTimes = 0;
				long failTimes =0 ;
				json.put("currentDate", entry.getKeyAsString());
				Terms codeTerms = entry.getAggregations().get("codeAgg");
				for (Terms.Bucket en : codeTerms.getBuckets()) {
					if((long)en.getKeyAsNumber()==HttpServletResponse.SC_OK){
						successTimes += en.getDocCount();
					}else{
						failTimes += en.getDocCount();
					}
				}
				json.put("allTimes", successTimes+failTimes);
				json.put("successTimes", successTimes);
				json.put("failTimes", failTimes);
				array.add(json);
			}
			return new JSONObject();
		}catch(Exception e){
			e.printStackTrace();
			logger.error(e.getMessage());
			return new JSONObject();
		}
	}
}
