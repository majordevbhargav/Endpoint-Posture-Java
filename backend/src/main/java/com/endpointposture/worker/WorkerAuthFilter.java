package com.endpointposture.worker;
import com.endpointposture.security.LoginRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;
/** Authenticates X-Worker-Key only on worker routes; enrollment uses a one-time token. */
@Component
public class WorkerAuthFilter extends OncePerRequestFilter {
    public static final String HEADER="X-Worker-Key";
    public static final String ENROLL_PATH="/api/v1/workers/enroll";
    private static final String PREFIX="/api/v1/workers/";
    private final WorkerRegistryService registry;
    private final WorkerAuditService audit;
    // Apply before the indexed hash lookup, so invalid-key floods cannot reach the database unbounded.
    private final LoginRateLimiter requestLimiter=new LoginRateLimiter(true,6000,6000,60);
    public WorkerAuthFilter(WorkerRegistryService registry,WorkerAuditService audit){this.registry=registry;this.audit=audit;}
    @Override protected boolean shouldNotFilter(@NonNull HttpServletRequest req){
        String uri=req.getRequestURI(); return !uri.startsWith(PREFIX)||ENROLL_PATH.equals(uri);
    }
    @Override protected void doFilterInternal(@NonNull HttpServletRequest req,@NonNull HttpServletResponse res,
                                               @NonNull FilterChain chain)throws ServletException,IOException{
        String key=req.getHeader(HEADER);
        if(key==null){chain.doFilter(req,res);return;}
        String ip=req.getRemoteAddr();
        if(!requestLimiter.tryAcquire(ip)){res.setStatus(429);res.setContentType("text/plain");res.getWriter().write("Too many requests");return;}
        WorkerRegistryService.AuthResult result=WorkerCredentials.looksLikeKey(key)?registry.authenticate(key):
          new WorkerRegistryService.AuthResult(WorkerRegistryService.Status.UNKNOWN_KEY,null,null,null);
        if(result.status()==WorkerRegistryService.Status.OK){
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
              result.principal(),null,List.of(new SimpleGrantedAuthority("ROLE_WORKER"))));
            chain.doFilter(req,res);return;
        }
        boolean revoked=result.status()==WorkerRegistryService.Status.REVOKED;
        audit.record(revoked?"AUTH_REVOKED":"AUTH_FAILED",result.workerId(),result.name(),
          revoked?"Revoked worker key presented":"Unknown or malformed worker key",ip);
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);res.setContentType("text/plain");res.getWriter().write("Invalid worker key");
    }
}