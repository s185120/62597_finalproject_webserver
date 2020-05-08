package Javalin;

import brugerautorisation.data.Bruger;
import brugerautorisation.transport.rmi.Brugeradmin;
import com.sun.org.apache.bcel.internal.generic.IF_ACMPEQ;
import common.ResponseObject;
import common.rmi.SkeletonRMI;
import io.javalin.Javalin;
import io.javalin.http.ServiceUnavailableResponse;
import io.javalin.http.UnauthorizedResponse;
import org.eclipse.jetty.http.HttpStatus;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.UUID;

public class Server {
    private final DateFormat DF = new SimpleDateFormat("[dd-MM-yyyy HH:mm:ss]");
    private Javalin app;
    private SkeletonRMI databaseServer;
    private Brugeradmin javabogServer;
    private HashMap<String, UserProfile> sessions = new HashMap<>();
    private final String UUID_COOKIE_NAME = "myfridge_uuid";

    public Server(SkeletonRMI databaseServer, Brugeradmin javabogServer) {
        this.databaseServer = databaseServer;
        this.javabogServer = javabogServer;
    }

    private String getCurrentTime() {
        return DF.format(Calendar.getInstance().getTimeInMillis());
    }

    private void validateUser(String uuid_cookie) {
        // If the user does not have a javalin userprofile, then they should not be allowed to do anything
        if (!sessions.containsKey(uuid_cookie)) {
            System.out.println(getCurrentTime() + " Unauthorized user attempted to call database");
            throw new UnauthorizedResponse("Unauthorized");
        }
        // If the user does not have a uuid in the database program, then no items can be fetched
        if (sessions.get(uuid_cookie).getDatabase_uuid() == null) {
            System.out.println(getCurrentTime() + " User does not have a database uuid");
            throw new ServiceUnavailableResponse("User has no database uuid");
        }
    }

    public void initialize() {
        javalinSetup();
        paths();
    }

    private void javalinSetup() {
        // Configuration of the javalin server
        app = Javalin.create(config -> {
            config.addStaticFiles("web");
        });

        // Starting the javalin server on port (80)
        app.start(80);

        app.before(ctx -> {
            System.out.println(getCurrentTime() + " Received '" + ctx.method() + "' on URL:" + ctx.url()
                    + " containing pathparams:" + ctx.pathParamMap() + " queryparams:" + ctx.queryParamMap()
                    + " formparams:" + ctx.formParamMap() + " cookies:" + ctx.cookieMap());
        });
    }

    private void paths() {

        app.post("/login", context -> {
            String username = context.queryParam("username");
            String password = context.queryParam("password");

            try {
                Bruger bruger = javabogServer.hentBruger(username, password);
                if (bruger != null) {
                    if (bruger.brugernavn.equals(username) && bruger.adgangskode.equals(password)) {
                        UserProfile userProfile = new UserProfile();
                        userProfile.setUsername(username);

                        // Giving the legitimate user their uuid
                        String uuid_cookie = UUID.randomUUID().toString();
                        while (sessions.containsKey(uuid_cookie)) {
                            uuid_cookie = UUID.randomUUID().toString();
                        }
                        userProfile.setJavalin_uuid(uuid_cookie);

                        // If the database responds successfully, then add its uuid of the user to the userprofile
                        ResponseObject ro = databaseServer.login(username, password);
                        if (ro != null) {
                            if (ro.getStatusCode() == 0) {
                                userProfile.setDatabase_uuid(ro.getResponseString());
                                ro = databaseServer.createUser(userProfile.getDatabase_uuid(), username);
                                if (ro != null && ro.getStatusCode() == 0) {
                                    System.out.println(getCurrentTime() + " Database program created new user");
                                } else {
                                    System.out.println(getCurrentTime() + " Database program unable to create new user");
                                }
                            } else {
                                System.out.println(getCurrentTime() + " Unable to login to the database server, error: " + ro.getStatusCode() + " " + ro.getStatusMessage());
                            }
                        } else {
                            System.out.println(getCurrentTime() + " Unable to login to the database server, error: " + ro.getStatusCode() + " " + ro.getStatusMessage());
                        }

                        sessions.put(uuid_cookie, userProfile);
                        System.out.println(getCurrentTime() + " User logged in and got cookie uuid: " + uuid_cookie + " and db uuid:" + sessions.get(uuid_cookie).toString());
                        context.cookieStore(UUID_COOKIE_NAME, uuid_cookie);
                        context.status(HttpStatus.OK_200);
                    } else {
                        System.out.println(getCurrentTime() + " User failed to login");
                        throw new UnauthorizedResponse("Failed login");
                    }
                }
            } catch (Exception e) {
                System.out.println(getCurrentTime() + " Exception: " + e.getMessage());
                throw new UnauthorizedResponse("Authentication error");
            }
        });

        app.get("/login", context -> {
            context.redirect("/");
        });

        app.post("/login/returning", context -> {
            String uuid_cookie = context.cookieStore(UUID_COOKIE_NAME);
            if (uuid_cookie != null && sessions.get(uuid_cookie) != null && !sessions.get(uuid_cookie).getDatabase_uuid().equals("")) {
                ResponseObject ro = databaseServer.validateUUID(sessions.get(uuid_cookie).getDatabase_uuid());
                if (ro != null) {
                    if (ro.getStatusCode() == 0) {
                        context.status(HttpStatus.ACCEPTED_202);
                        return;
                    }
                }
            }

            context.status(HttpStatus.UNAUTHORIZED_401);
        });

        app.post("/login/forgot", context -> {
            String username = context.queryParam("username");
            try {
                javabogServer.sendGlemtAdgangskodeEmail(username,"Send fra MyFridge");
                context.status(HttpStatus.OK_200);
            } catch (Exception e) {
                System.out.println(getCurrentTime() + " /login/forgot exception: " + e.getMessage());
                throw new ServiceUnavailableResponse("Unexpected error :(");
            }
        });

        app.get("/fridge/items", context -> {
            String uuid_cookie = context.cookieStore(UUID_COOKIE_NAME);
            validateUser(uuid_cookie);

            ResponseObject ro = databaseServer.getUser(sessions.get(uuid_cookie).database_uuid,sessions.get(uuid_cookie).username);
            if (ro.getStatusCode() == 0) {
                int fridgeID = Integer.parseInt(ro.getResponseArraylist().get(1)[1]);
                System.out.println(getCurrentTime() + " Found fridge ID: " + fridgeID);

                ro = databaseServer.getFridgeContents(sessions.get(uuid_cookie).database_uuid, fridgeID);
                if (ro.getStatusCode() == 0) {
                    System.out.println(getCurrentTime() + " Sending JSON object");
                    context.status(HttpStatus.OK_200);
                    context.json(ro.getResponseArraylist());
                }
            }
        });

        app.get("/fridge/new-item/types", context -> {
            String uuid_cookie = context.cookieStore(UUID_COOKIE_NAME);
            validateUser(uuid_cookie);

            ResponseObject ro = databaseServer.getTypes(sessions.get(uuid_cookie).getDatabase_uuid());
            if (ro != null && ro.getStatusCode() == 0) {
                System.out.println(getCurrentTime() + " Sending JSON object");
                context.status(HttpStatus.OK_200);
                context.json(ro.getResponseArraylist());
            }
        });

        app.put("/user/change-password", context -> {
            // TODO: Kalde på brugerautorisation for at skifte brugerens password
        });







        /*
        app.get("/", context -> {
            context.redirect("/login");
        });

        app.get("/login", context -> {
                    context.render("webapp/login.html");
        });

        app.get("/login/:username", context -> {
            javaprogram.informConnect();

            String username = context.pathParam("username");
            String password = context.queryParam("password");

            boolean success = javaprogram.login(username, password);
            if (success) {
                System.out.println(getTime() + "Login success");
                context.status(HttpStatus.ACCEPTED_202);
                context.render("webapp/indes.html");
            } else {
                System.out.println(getTime() + "Login failed");
                context.status(HttpStatus.UNAUTHORIZED_401);
            }

        });

        app.get("/login/forgot", context -> {
            context.render("webapp/index.html");
        });

        app.get("/login/forgot/:username", context -> {
            String username = context.pathParam("username");
            String message = context.queryParam("message");
            if (message == null) {
                message = "";
            }

            boolean success = javaprogram.forgotPassword(username, message);
            if (success) {
                context.status(HttpStatus.OK_200);
            } else {
                context.status(HttpStatus.SERVICE_UNAVAILABLE_503);
            }
        });

        app.get("/account/changePassword/:oldPassword", context -> {
            context.status(HttpStatus.UNAUTHORIZED_401).result("<h1>401 Unauthorized</h1>You are not authorized to see this page.").contentType("text/html");

            String oldPassword = context.pathParam("oldPassword");
            String newPassword = context.queryParam("newPassword");

            Bruger bruger = javaprogram.changePassword(oldPassword, newPassword);
            if (bruger != null) {
                context.status(HttpStatus.ACCEPTED_202);
            } else {
                context.status(HttpStatus.SERVICE_UNAVAILABLE_503);
            }
        });
        */
    }

}
