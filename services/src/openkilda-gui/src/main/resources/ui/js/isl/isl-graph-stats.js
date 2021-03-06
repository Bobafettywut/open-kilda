/*<![CDATA[*/
/** Below the javascript/ajax/jquery code to generate and display the stats api results.
* By default api will show stats of the previous day
* user can generate stats via filter elements on the html page */


/**
* Execute  getGraphData function when onchange event is fired 
* on the filter input values of datetimepicker, downsampling and menulist.
*/

var linkData = localStorage.getItem("linkData");	
var obj = JSON.parse(linkData)
var sourceSwitch = obj.source_switch;
var targetSwitch = obj.target_switch;
var sourcePort = obj.src_port;
var targetPort =obj.dst_port;
var source = common.toggleSwitchID(sourceSwitch);
var target = common.toggleSwitchID(targetSwitch);
var selMetric="latency";
var graphInterval;

$(function() {
	
	$('#timezone').on('change',function(){
		var timezone = $('#timezone option:selected').val();
		var dat2 = new Date();
		var dat1 = new Date(dat2.getTime());
		dat1.setDate(dat2.getDate() - 1);		
		if(timezone == 'UTC'){
			var startDate = moment(dat1).utc().format("YYYY/MM/DD HH:mm:ss");
			var endDate = moment(dat2).utc().format("YYYY/MM/DD HH:mm:ss");
			$('#datetimepicker7ISL').val(startDate);
			$('#datetimepicker8ISL').val(endDate)
		}else{
			var startDate = moment(dat1).format("YYYY/MM/DD HH:mm:ss");
			var endDate = moment(dat2).format("YYYY/MM/DD HH:mm:ss");
			$('#datetimepicker7ISL').val(startDate);
			$('#datetimepicker8ISL').val(endDate)
		}
	})
	$("#datetimepicker7ISL,#datetimepicker8ISL,#downsamplingISL,#autoreloadISL,#timezone").on("change",function() {
		if($('#selectedGraph').val() == 'isl'){
			getGraphData();
		}
	});
	$('#selectedGraph').on('change',function(e){
		if($(this).val() == 'isl'){
			getGraphData(true);
		}else{
			if(graphInterval){
				clearInterval(graphInterval);
			}
		}
	})
});
/**
* Execute this function when page is loaded
* or when user is directed to this page.
*/
$(document).ready(function() {
	
	$.datetimepicker.setLocale('en');
	$('#timezone').val("LOCAL");
	var date = new Date()
	var yesterday = new Date(date.getTime());
	yesterday.setDate(date.getDate() - 1);
	var YesterDayDate = moment(yesterday).format("YYYY/MM/DD HH:mm:ss");
    var EndDate = moment(date).format("YYYY/MM/DD HH:mm:ss");
    if($('#timezone option:selected').val() == 'UTC'){
    	var convertedStartDate = moment(yesterday).format("YYYY-MM-DD-HH:mm:ss");
    	var convertedEndDate = moment(date).format("YYYY-MM-DD-HH:mm:ss");
    }else{
    	var convertedStartDate = moment(yesterday).utc().format("YYYY-MM-DD-HH:mm:ss");
    	var convertedEndDate = moment(date).utc().format("YYYY-MM-DD-HH:mm:ss");
    }
	
	var downsampling = "30s";
	$("#downsamplingISL").val(downsampling)
	$("#datetimepicker7ISL").val(YesterDayDate);
	$("#datetimepicker8ISL").val(EndDate);
	$('#datetimepicker7ISL').datetimepicker({
		  format:'Y/m/d H:i:s',
	});
	$('#datetimepicker8ISL').datetimepicker({
		  format:'Y/m/d H:i:s',
	});
	$('#datetimepicker_dark').datetimepicker({theme:'dark'})
	loadGraph.loadGraphData("/stats/isl/"+source+"/"+sourcePort+"/"+target+"/"+targetPort+"/"+convertedStartDate+"/"+convertedEndDate+"/30s/"+selMetric,"GET",selMetric).then(function(response) {
		var timezone = $('#timezone option:selected').val();
		if(response && response.length && typeof(response[0].tags)!=='undefined' ){
			response[0].tags.direction ="F"; // setting direction to forward
		}
		// calling reverse isl detail
		var reverseUrl = "/stats/isl/"+target+"/"+targetPort+"/"+source+"/"+sourcePort+"/"+convertedStartDate+"/"+convertedEndDate+"/30s/"+selMetric
		loadGraph.loadGraphData(reverseUrl,"GET",selMetric).then(function(responseReverse) {
			$("#wait1").css("display", "none");
			$('body').css('pointer-events', 'all');
			if(responseReverse && responseReverse.length && typeof(responseReverse[0].tags)!=='undefined' ){
				responseReverse[0].tags.direction ="R"; // setting direction to reverse
			}
			var responseData =response;
			responseData.push(responseReverse[0]);
			showStatsGraph.showStatsData(responseData,selMetric,null,null,null,null,timezone);
		})	
		
	})
})


/**
* Execute this function to  show stats data whenever user filters data in the
* html page.
*/
function getGraphData(changeFlag) {
	var regex = new RegExp("^\\d+(s|h|m){1}$");
	var currentDate = new Date();
	var startDate = new Date($("#datetimepicker7ISL").val());
	var endDate =  new Date($("#datetimepicker8ISL").val());
	var timezone = $('#timezone option:selected').val();
	if(timezone == 'UTC'){
		var convertedStartDate = moment(startDate).format("YYYY-MM-DD-HH:mm:ss");	
		var convertedEndDate = moment(endDate).format("YYYY-MM-DD-HH:mm:ss");
	}else{
		var convertedStartDate = moment(startDate).utc().format("YYYY-MM-DD-HH:mm:ss");	
		var convertedEndDate = moment(endDate).utc().format("YYYY-MM-DD-HH:mm:ss");
	}
	
	var downsampling = $("#downsamplingISL").val();
	var downsamplingValidated = regex.test(downsampling);
	var valid=true;
	if(downsamplingValidated == false) {	
	
		$("#DownsampleID").addClass("has-error")	
		$(".downsample-error-message").html("Please enter valid input.");		
		valid=false;
		return
	}
	if(startDate.getTime() >= currentDate.getTime()) {

		$("#fromId").addClass("has-error")	
		$(".from-error-message").html("From date should not be greater than currentDate.");		
		valid=false;
		return;
	} else if(endDate.getTime() <= startDate.getTime()){
		$("#toId").addClass("has-error")	
		$(".to-error-message").html("To date should not be less than from date.");	
		valid=false;
		return;
	}
	
	var autoreload = $("#autoreloadISL").val();
	var numbers = /^[-+]?[0-9]+$/;  
	var checkNo = $("#autoreloadISL").val().match(numbers);
	var checkbox =  $("#check").prop("checked");
	
	var test = true;	
    autoVal.reloadValidation(function(valid){
	  
	  if(!valid) {
		  test = false;		  
		  return false;
	  }
  });
  
if(test) {
	$('#wait1').show();
	$("#fromId").removeClass("has-error")
    $(".from-error-message").html("");
	
	$("#toId").removeClass("has-error")
    $(".to-error-message").html("");
	
	$("#autoreloadId").removeClass("has-error")
    $(".error-message").html("");
	
  	$("#DownsampleID").removeClass("has-error")
	$(".downsample-error-message").html("");
  	if(typeof(changeFlag)!='undefined' &&  changeFlag){
  		var loadUrl ="/stats/isl/"+source+"/"+sourcePort+"/"+target+"/"+targetPort+"/"+convertedStartDate+"/"+convertedEndDate+"/"+"30s"+"/"+selMetric;
  	}else{
  		var loadUrl ="/stats/isl/"+source+"/"+sourcePort+"/"+target+"/"+targetPort+"/"+convertedStartDate+"/"+convertedEndDate+"/"+downsampling+"/"+selMetric;
  	}
  	
	loadGraph.loadGraphData(loadUrl,"GET",selMetric).then(function(response) {
		if(response && response.length && typeof(response[0].tags)!=='undefined' ){
			response[0].tags.direction ="F";
		}
		if(typeof(changeFlag)!='undefined' &&  changeFlag){
	  		var reverseLoadUrl ="/stats/isl/"+target+"/"+targetPort+"/"+source+"/"+sourcePort+"/"+convertedStartDate+"/"+convertedEndDate+"/"+"30s"+"/"+selMetric;
	  	}else{
	  		var reverseLoadUrl ="/stats/isl/"+target+"/"+targetPort+"/"+source+"/"+sourcePort+"/"+convertedStartDate+"/"+convertedEndDate+"/"+downsampling+"/"+selMetric;
	  	}
		loadGraph.loadGraphData(reverseLoadUrl,"GET",selMetric).then(function(responseReverse) {
			if(responseReverse && responseReverse.length && typeof(responseReverse[0].tags)!=='undefined' ){
				responseReverse[0].tags.direction ="R";
			}
			var responseData =response;
			responseData.push(responseReverse[0]);
			$("#wait1").css("display", "none");
			$('body').css('pointer-events', 'all');
			showStatsGraph.showStatsData(responseData,selMetric,null,null,null,null,timezone); 
		})
		
})
	
			try {
				clearInterval(graphInterval);
			} catch(err) {

			}
			
			if(autoreload){
				graphInterval = setInterval(function(){
					callIntervalData() 
				}, 1000*autoreload);
			}
	}	
}
		
function callIntervalData(){
	var currentDate = new Date();
	var startDate = new Date($("#datetimepicker7ISL").val());
	var timezone = $('#timezone option:selected').val();
	var savedEnddate = new Date($('#savedEnddate').val());
	var autoreload = $("#autoreloadISL").val();
	savedEnddate = new Date(savedEnddate.getTime() + (autoreload * 1000));
	selMetric = 'latency';
	$('#savedEnddate').val(savedEnddate);
	var endDate =savedEnddate ;// new Date() ||
	if(timezone == 'UTC'){
		var convertedStartDate = moment(startDate).format("YYYY-MM-DD-HH:mm:ss");	
		var convertedEndDate = moment(endDate).format("YYYY-MM-DD-HH:mm:ss");
	}else{
		var convertedStartDate = moment(startDate).utc().format("YYYY-MM-DD-HH:mm:ss");	
		var convertedEndDate = moment(endDate).utc().format("YYYY-MM-DD-HH:mm:ss");
	}
		
	var downsampling =$("#downsamplingISL").val()	
	$('#wait1').show();

	loadGraph.loadGraphData("/stats/isl/"+source+"/"+sourcePort+"/"+target+"/"+targetPort+"/"+convertedStartDate+"/"+convertedEndDate+"/30s/"+selMetric,"GET",selMetric).then(function(response) {
		if(response && response.length && typeof(response[0].tags)!=='undefined' ){
			response[0].tags.direction ="F"; // setting direction to forward
		}
		// calling reverse isl detail
		var reverseUrl = "/stats/isl/"+target+"/"+targetPort+"/"+source+"/"+sourcePort+"/"+convertedStartDate+"/"+convertedEndDate+"/30s/"+selMetric
		loadGraph.loadGraphData(reverseUrl,"GET",selMetric).then(function(responseReverse) {
			$("#wait1").css("display", "none");
			$('body').css('pointer-events', 'all');
			if(responseReverse && responseReverse.length && typeof(responseReverse[0].tags)!=='undefined' ){
				responseReverse[0].tags.direction ="R"; // setting direction to reverse
			}
			var responseData =response;
			responseData.push(responseReverse[0]);
			showStatsGraph.showStatsData(responseData,selMetric,null,null,null,null,timezone);
		})	
	});
}

/* ]]> */