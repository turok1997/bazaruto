package ch.bazaruto;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* The main class to create Bazaruto apps */
@SuppressWarnings("rawtypes") 
public class Bazaruto extends NanoHTTPD {

    /* Annotation for Controller routes */
    public @Retention(RetentionPolicy.RUNTIME) @interface Route {
        String value();
    }
    
    /* Annotation for GET Methods */
    public @Retention(RetentionPolicy.RUNTIME) @interface GET {
        String value();
    }
    
    /* Annotation for POST Methods */
    public @Retention(RetentionPolicy.RUNTIME) @interface POST {
        String value();
    }
    
    /* Annotation for PUT Methods */
    public @Retention(RetentionPolicy.RUNTIME) @interface PUT {
        String value();
    }
    
    /* Annotation for DELETE Methods */
    public @Retention(RetentionPolicy.RUNTIME) @interface DELETE {
        String value();
    }
    
    private Hashtable<Pattern, Class> controllers = new Hashtable<Pattern, Class>();
    private Hashtable<Pattern, File> staticPaths = new Hashtable<Pattern, File>();
    
    public void addController(Class controller) {
        @SuppressWarnings("unchecked")
        Annotation annotation = controller.getAnnotation(Route.class);
        if (annotation instanceof Route){
            Pattern url_pattern = Pattern.compile(((Route)annotation).value());
            controllers.put(url_pattern, controller);
        } else {
            throw new RuntimeException(controller.getName() + 
                    " does not define a route! Use @Route to do so"); 
        }
    }
    
    public void removeController(Class controller){
        for (Entry<Pattern, Class> e: controllers.entrySet()) {
            if (e.getValue() == controller)
                controllers.remove(e.getKey());
        }
    }
    
    public void addStaticPath(String path, File folder) {
        Pattern url_pattern = Pattern.compile(path);
        staticPaths.put(url_pattern, folder);
    }
    
    public Response dispatch(Request req) {
    	Log.info("Req: " + req.uri);
    	
        for (Entry<Pattern, Class> entry: controllers.entrySet()) {
            Pattern url_pattern = entry.getKey();
            Class controller = entry.getValue();
            if (url_pattern.matcher(req.uri).find()) {
                req.path = req.uri.replaceAll(url_pattern.pattern(), "");
                Log.info("Dispatched to:" + controller.getName());
                return dispatchToMethod(req, controller);
            }
        }
        
        for (Entry<Pattern, File> entry: staticPaths.entrySet()) {
            Pattern url_pattern = entry.getKey();
            File path = entry.getValue();
            if (url_pattern.matcher(req.uri).find()) {
                req.uri = req.uri.replaceAll(url_pattern.pattern(), "");
                Log.info("Dispatched to:" + path.getAbsolutePath());
                return serveFile(req, path, false);
            }
        }
        
        return new Response("Page not found: No Controller registered to this uri",
                NanoHTTPD.HTTP_NOTFOUND);
    }
    
    private Response dispatchToMethod(Request req, Class controller) {
        Method methods[] = controller.getDeclaredMethods();
        
        for (Method method : methods) {
            // Dispatch GET
            Annotation get_annotation = method.getAnnotation(GET.class);
            if(get_annotation instanceof GET && req.method.equalsIgnoreCase("GET")){
                String url_pattern = ((GET)get_annotation).value();
                if (req.path.matches(url_pattern)) {
                    return executeRequest(req, controller, method, url_pattern);
                }
            }
            
            // Dispatch POST
            Annotation post_annotation = method.getAnnotation(POST.class);
            if(post_annotation instanceof POST && req.method.equalsIgnoreCase("POST")){
                String url_pattern = ((POST)post_annotation).value();
                if (req.path.matches(url_pattern)) {
                    return executeRequest(req, controller, method, url_pattern);
                }
            }
            
            // Dispatch PUT
            Annotation put_annotation = method.getAnnotation(PUT.class);
            if(put_annotation instanceof PUT && req.method.equalsIgnoreCase("PUT")){
                String url_pattern = ((PUT)put_annotation).value();
                if (req.path.matches(url_pattern)) {
                    return executeRequest(req, controller, method, url_pattern);
                }
            }
            
            // Dispatch DELETE
            Annotation delete_annotation = method.getAnnotation(DELETE.class);
            if(delete_annotation instanceof DELETE && req.method.equalsIgnoreCase("DELETE")){
                String url_pattern = ((DELETE)delete_annotation).value();
                if (req.path.matches(url_pattern)) {
                    return executeRequest(req, controller, method, url_pattern);
                }
            }
        }
        
        return new Response("Page not found: url " + req.uri + " not registered",
                NanoHTTPD.HTTP_NOTFOUND);
    }
    
    private Response executeRequest(Request req, Class controller, Method method, String url_pattern) {
        try {
            Object instance = controller.newInstance();
            Object[] parameters = extractParameters(req, url_pattern);
            Response res = (Response)method.invoke(instance, parameters);
            return res;
            
        // TODO: Better error handling here would be helpfull!
        } catch (InstantiationException e) {
            return new Response("Error: Could not instantiate controller " + 
                    controller.getName() + ". ?" +
                    e.getMessage(),
                    NanoHTTPD.HTTP_INTERNALERROR);
        } catch (IllegalAccessException e) {
            return new Response("Error: Could not instantiate controller "
                    + controller.getName() + ". " + e.getMessage(),
                    NanoHTTPD.HTTP_INTERNALERROR);
        } catch (IllegalArgumentException e) {
            return new Response("Error: Could not invoke method: Wrong number or arguments\n" +
                    e.getMessage(),
                    NanoHTTPD.HTTP_INTERNALERROR);
        } catch (InvocationTargetException e) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            return new Response(sw.toString().replaceAll("\n","<br>"),
                    NanoHTTPD.HTTP_INTERNALERROR);
        } catch (SecurityException e) {
            return new Response("Error: Could not instantiate controller: Security Exception\n" + 
                    e.getMessage(),
                    NanoHTTPD.HTTP_INTERNALERROR);
        }
    }
    
    private Object[] extractParameters(Request req, String url_pattern) {
        LinkedList<Object> parameters = new LinkedList<Object>();
        parameters.add(req);
        
        Pattern pattern = Pattern.compile(url_pattern);
        Matcher matcher = pattern.matcher(req.path);
        if (matcher.matches()) {
            for(int i=1; i <= matcher.groupCount(); i++) {
                String group = matcher.group(i);
                try {
                    Integer integer = Integer.parseInt(group);
                    parameters.add(integer);
                    continue;
                } catch (NumberFormatException nfe) {}
                try {
                    Double dou = Double.parseDouble(group);
                    parameters.add(dou);
                    continue;
                } catch (NumberFormatException nfe) {}
                
                parameters.add(group);
            }
        }
        return parameters.toArray();
        
    }
    
    @Override
    public Response serve(Request req) {
        return this.dispatch(req);
    }
}
