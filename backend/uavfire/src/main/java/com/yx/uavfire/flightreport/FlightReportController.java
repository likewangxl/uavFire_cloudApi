package com.yx.uavfire.flightreport;
import com.dji.sdk.common.HttpResultResponse;
import com.yx.uavfire.common.model.CustomClaim;
import com.yx.uavfire.component.AuthInterceptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;
@RestController
@RequestMapping("${url.wayline.prefix}${url.wayline.version}/workspaces")
public class FlightReportController {
    private final FlightReportService reports;
    public FlightReportController(FlightReportService reports){this.reports=reports;}
    @GetMapping("/{workspaceId}/planned-waylines/{taskId}/flight-report")
    public HttpResultResponse<Map<String,Object>> get(@PathVariable String workspaceId,@PathVariable String taskId,
            @RequestParam(required=false) String runId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize,
            @RequestParam(required=false) Long snapshotRecordId,HttpServletRequest request){
        Object value=request.getAttribute(AuthInterceptor.TOKEN_CLAIM);
        if(!(value instanceof CustomClaim))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        CustomClaim claim=(CustomClaim)value;if(!workspaceId.equals(claim.getWorkspaceId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return HttpResultResponse.success(reports.report(workspaceId,taskId,runId,page,pageSize,snapshotRecordId));
    }
    @GetMapping("/{workspaceId}/planned-waylines/{taskId}/flight-report/related")
    public HttpResultResponse<Map<String,Object>> related(@PathVariable String workspaceId,@PathVariable String taskId,@RequestParam String runId,@RequestParam String kind,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="100") int pageSize,@RequestParam(required=false) Long snapshotRecordId,HttpServletRequest request){
        Object value=request.getAttribute(AuthInterceptor.TOKEN_CLAIM);
        if(!(value instanceof CustomClaim))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(!workspaceId.equals(((CustomClaim)value).getWorkspaceId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return HttpResultResponse.success(reports.related(workspaceId,taskId,runId,kind,page,pageSize,snapshotRecordId));
    }
}
