
var defaultPPId = 0;



$(document).ready(function(){
	
	initUI();
	attachFormValidation();
	
	$('#radioNoPause').attr('checked','checked');

});

function getMaxCpuTimeout(){
	maxtime=$( "#workerQueue option:selected" ).attr("cpumax");
	return parseInt(maxtime);
}

function getMaxWallTimeout() {
	maxtime=$( "#workerQueue option:selected" ).attr("wallmax");
	return parseInt(maxtime);
}

function getCpuTimeoutErrorMessage() {
	timeout=getMaxCpuTimeout();
	if (isNaN(timeout)) {
		return "please select a queue";
	}
	return timeout+" second max timeout";
}

function getClockTimeoutErrorMessage() {
	timeout=getMaxWallTimeout();
	if (isNaN(timeout)) {
		return "please select a queue";
	}
	return timeout+" second max timeout";
}

/**
 * Attach validation to the job creation form
 */
function attachFormValidation(){
	// Add regular expression capabilities to the validator
	$.validator.addMethod(
			"regex", 
			function(value, element, regexp) {
				var re = new RegExp(regexp);
				return this.optional(element) || re.test(value);
	});
	
	
	
	// Set up form validation
	$("#addForm").validate({
		rules: {
			name: {
				required: true,
				minlength: 2,
				maxlength: $("#txtJobName").attr("length"),
				regex : getPrimNameRegex()
			},
			desc: {
				required: false,
				maxlength: $("#txtDesc").attr("length"),
				regex: getPrimDescRegex()
			},
			cpuTimeout: {
				required: true,			    
			    max: getMaxCpuTimeout(),
			    min: 1
			},
			wallclockTimeout: {
				required: true,			    
			    max: getMaxWallTimeout(),
			    min: 1
			},
			maxMem: {
				required: true,
				min : 0 
			},
			queue: {
				required: true
			}
		},
		messages: {
			name:{
				required: "enter a job name",
				minlength: "2 characters minimum",
				maxlength: $("#txtJobName").attr("length") + " characters maximum",
				regex: "invalid character(s)"
			},
			desc: {
				required: "enter a job description",
				maxlength: $("#txtDesc").attr("length") + " characters maximum",
				regex: "invalid character(s)"
			},
			cpuTimeout: {
				required: "enter a timeout",			    
			    max: getCpuTimeoutErrorMessage(),
			    min: "1 second minimum timeout"
			},
			wallclockTimeout: {
				required: "enter a timeout",			    
			    max: getClockTimeoutErrorMessage(),
			    min: "1 second minimum timeout"
			},
			maxMem: {
				required: "enter a maximum memory",
				max: "100 gigabytes maximum" 
			},
			queue: {
				required: "error - no worker queues"
			}
		}
	});
	
	//when we change queues, we need to refresh the validation to use the new timeouts
	$("#workerQueue").change(function() {
		settings = $('#addForm').validate().settings;
		settings.rules.cpuTimeout = {
				required: true,			    
			    max: getMaxCpuTimeout(),
			    min: 1
			};
		
		settings.rules.wallclockTimeout = {
				required: true,			    
			    max: getMaxWallTimeout(),
			    min: 1
			};
		
		settings.messages.cpuTimeout = {
				required: "enter a timeout",			    
			    max: getMaxCpuTimeout()+" second max timeout",
			    min: "1 second minimum timeout"
			};
		
		settings.messages.wallclockTimeout = {
				required: "enter a timeout",			    
			    max: getMaxWallTimeout()+" second max timeout",
			    min: "1 second minimum timeout"
		};
		$("#addForm").valid(); //revalidate now that we have new rules

		
	});
};


/**
 * Sets up the jQuery button style and attaches click handlers to those buttons.
 */
function initUI() {
	
	// Set the selected post processor to be the default one
	
	defaultPPId = $('#postProcess').attr('default');
	defaultBPId = $('#benchProcess').attr("default");
	if (stringExists(defaultPPId)) {
		$('#postProcess option[value=' + defaultPPId + ']').attr('selected', 'selected');
	}
	
	if (stringExists(defaultBPId)) {
		$('#benchProcess option[value=' + defaultBPId + ']').attr('selected', 'selected');

	}
	
	//If there is only one post processor and for some reason it is not the default, set it as such
	if ($("#postProcess").find("option").length==2) {
		$("#postProcess").find("option").last().attr("selected","selected");
	}
	
	//do the same for bench processors
	if ($("#benchProcess").find("option").length==2) {
		$("#benchProcess").find("option").last().attr("selected","selected");
	}
	
	defaultPPId = $('#preProcess').attr('default');
	if (stringExists(defaultPPId)) {
		$('#preProcess option[value=' + defaultPPId + ']').attr('selected', 'selected');
	}
	
	//If there is only one pre processor and for some reason it is not the default, set it as such
	if ($("#preProcess").find("option").length==2) {
		$("#preProcess").find("option").last().attr("selected","selected");
	}
	
	$('#btnBack').button({
		icons: {
			primary: "ui-icon-arrowthick-1-w"
	}}).click(function(){
		
		history.back(-1);
	});
	$("#advancedSettings").expandable(true);
    $('#btnDone').button({
		icons: {
			secondary: "ui-icon-check"
		}
    }).click(function(){

 		createDialog("Creating your job, please wait. This will take some time for large jobs.");
    });

}
