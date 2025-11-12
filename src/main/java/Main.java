import io.javalin.Javalin;
import io.javalin.http.Context;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Single-file demo backend: Javalin + JDBC (no ORM), one table, GET/POST /posts
public class Main {

    // ======== BEGIN: DB CONFIG (edit these for your setup) ========
    // If you use XAMPP, default port is often 3306; user often "root" with empty password.
    private static final String DB_URL  = "jdbc:mysql://localhost:3306/hyperlocal?useSSL=false&serverTimezone=UTC";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "";
    // ======== END: DB CONFIG ========


    //MAIN API ROUTE METHOD
    public static void main(String[] args) throws Exception {
        // 1) Ensure table exists
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            System.out.println("Connected to database successfully!");
        } catch (SQLException e) {
            System.err.println("DB connection failed. Check DB_URL/USER/PASS in Main.java");
            e.printStackTrace();
            return;
        }

        // 2) Start Javalin
        Javalin app = Javalin.create(config -> {
            // ✅ Simple CORS setup for React on port 5173
            config.plugins.enableCors(cors -> {
                cors.add(it -> it.allowHost("http://localhost:5173"));
            });
        }).start(7071);
             
        

        // Health check
        app.get("/health", ctx -> ctx.result("HELLLOOOOO"));

        //Login / Registration
        app.post("/auth/register", Main::register);
        app.post("/auth/login", Main::login);

        // GET all posts (returns JSON array)
        app.get("/posts", Main::getAllPosts);

        // POST a new post
        app.post("/posts", Main::createPost);
    }


    //INTERFACES
    // Model class (Javalin/Jackson will serialize this to JSON automatically)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Post {
        public Long id;
        public Long userId;
        public String type;
        public String title;
        public String description;
        public Double lat;
        public Double lng;
        public String contact;
        public String status;
        public String createdAt;
        public String username;
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class User {
        public Long id;
        public String username;
        public String email;
        public String phone;
        public Double rating;
        public Integer ratingCount;
}

    public static class AuthRequest {
        public String username;
        public String email;
        public String password;
        public String phone;
    }

    //AUTHENTICATION SERVICES
    private static void register(Context ctx)
    {
        AuthRequest req = ctx.bodyAsClass(AuthRequest.class);

        if(req.email == null || req.password==null || req.username == null){
            ctx.status(400).result("Missing required fields");
            return;
        }
        String sql = "INSERT INTO users(username,email,password,phone,rating,rating_count,created_at) VALUES (?,?,?,?,?,?,?)";
        try(Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS))
        {
            PreparedStatement ps = conn.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);

            ps.setString(1,req.username);
            ps.setString(2, req.email);
            ps.setString(3, req.password);
            ps.setString(4, req.phone!=null?req.phone:"");
            ps.setDouble(5,0.0); // initial rating
            ps.setInt(6,0); // initial rating count
            ps.setTimestamp(7, Timestamp.from(Instant.now()));
            ps.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys();
            if(keys.next()){
                User user = new User();
                user.id = keys.getLong(1);
                user.username = req.username;
                user.email = req.email;
                user.phone = req.phone;
                user.rating = 0.0;
                user.ratingCount = 0;
                ctx.status(201).json(user);
            }
        }
        catch(SQLException e){
            e.printStackTrace();
            ctx.status(500).result("Registration failed: " + e.getMessage());
        }
    }

    private static void login(Context ctx){
        AuthRequest req = ctx.bodyAsClass(AuthRequest.class);

        if(req.email == null || req.password == null)
        {
            ctx.status(400).result("Missing email or password");
            return;
        }
        String sql = "SELECT id,username,email,phone,rating,rating_count FROM users WHERE email = ? AND password = ?";
        try(Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS))
        {
            PreparedStatement ps = conn.prepareStatement(sql);
            
            ps.setString(1, req.email);
            ps.setString(2,req.password);
            ResultSet rs = ps.executeQuery();

            if(rs.next())
            {
                User user = new User();
                user.id = rs.getLong("id");
                user.username = rs.getString("username");
                user.email = rs.getString("email");
                user.phone = rs.getString("phone");
                user.rating = rs.getDouble("rating");
                user.ratingCount = rs.getInt("rating_count");
                ctx.json(user);
            }
            else{
                ctx.status(401).result("Invalid email or password");
            }
        }
        catch(SQLException e){
            e.printStackTrace();
            ctx.status(500).result("Login Failed: "+ e.getMessage());

        }

    }


    //GET METHODS

    //get all posts
    private static void getAllPosts(Context ctx) {
        String sql = "SELECT id, type, title, description, contact, lat, lng, created_at FROM posts";
        List<Post> posts = new ArrayList<>();
    
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
    
            while (rs.next()) {
                Post p = new Post();
                p.id = rs.getLong("id");
                p.type = rs.getString("type");
                p.title = rs.getString("title");
                p.description = rs.getString("description");
                p.contact = rs.getString("contact"); // ✅ added
                p.lat = rs.getDouble("lat");
                p.lng = rs.getDouble("lng");
                p.createdAt = rs.getTimestamp("created_at").toInstant().toString();
                posts.add(p);
            }
    
            ctx.json(posts);
        } catch (SQLException e) {
            e.printStackTrace();
            ctx.status(500).result("DB error: " + e.getMessage());
        }
    }
    

    //POST METHODS
    //create post
    private static void createPost(Context ctx) {
        // Validate basic body (Jackson maps JSON -> Post)
        Post input = ctx.bodyAsClass(Post.class);
        if (input == null || input.title == null || input.title.isBlank()
                || input.type == null || input.type.isBlank()
                || input.lat == null || input.lng == null || input.userId == null) {
            ctx.status(400).result("Missing required fields: userId, type, title, lat, lng");
            return;
        }

        String sql = "INSERT INTO posts(user_id, type, title, description, lat, lng, contact,status, created_at) VALUES (?,?,?,?,?,?,?,?,?)";
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
         PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

        ps.setLong(1,input.userId);
        ps.setString(2, input.type);
        ps.setString(3, input.title);
        ps.setString(4, input.description != null ? input.description : "");
        ps.setDouble(5, input.lat);
        ps.setDouble(6, input.lng);
        ps.setString(7, input.contact != null ? input.contact : "");
        ps.setString(8,"active");
        Timestamp now = Timestamp.from(Instant.now());
        ps.setTimestamp(9, now);

        ps.executeUpdate();

        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (keys.next()) {
                input.id = keys.getLong(1);
            }
        }

        input.createdAt = now.toInstant().toString();
        ctx.status(201).json(input);

        } 
        catch (SQLException e) {
            e.printStackTrace();
            ctx.status(500).result("DB error: " + e.getMessage());
        }
    }

}