import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JOptionPane;

public class DatabaseHelper {
    private final String dbUrl;

    public DatabaseHelper(String dbFilePath) {
        this.dbUrl = "jdbc:sqlite:" + dbFilePath;
        initDatabase();
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    private void initDatabase() {
        String createBooks = """
            CREATE TABLE IF NOT EXISTS books (
                isbn TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                author TEXT NOT NULL,
                genre TEXT,
                is_available INTEGER NOT NULL
            );
            """;

        String createUsers = """
            CREATE TABLE IF NOT EXISTS users (
                user_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                contact TEXT
            );
            """;

        String createBorrowings = """
            CREATE TABLE IF NOT EXISTS borrowings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id TEXT NOT NULL,
                isbn TEXT NOT NULL,
                borrow_date TEXT NOT NULL,
                return_date TEXT,
                FOREIGN KEY(user_id) REFERENCES users(user_id),
                FOREIGN KEY(isbn) REFERENCES books(isbn)
            );
            """;

        try (Connection conn = getConnection();
             Statement st = conn.createStatement()) {
            st.execute(createBooks);
            st.execute(createUsers);
            st.execute(createBorrowings);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public boolean addBook(Book book) {
        String sql = "INSERT INTO books(isbn, title, author, genre, is_available) VALUES(?,?,?,?,?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, book.isbn);
            ps.setString(2, book.title);
            ps.setString(3, book.author);
            ps.setString(4, book.genre);
            ps.setInt(5, book.isAvailable ? 1 : 0);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            // Optionally inspect e.getMessage() to check for duplicate primary key, etc.
            e.printStackTrace();
            return false;
        }
    }

    public boolean registerUser(User user) {
        String sql = "INSERT INTO users(user_id, name, contact) VALUES(?,?,?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, user.userId);
            ps.setString(2, user.name);
            ps.setString(3, user.contact);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public List<Book> getAllBooks() {
        List<Book> list = new ArrayList<>();
        String sql = "SELECT * FROM books";
        try (Connection conn = getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Book b = new Book(
                        rs.getString("title"),
                        rs.getString("author"),
                        rs.getString("isbn"),
                        rs.getString("genre")
                );
                b.isAvailable = rs.getInt("is_available") == 1;
                list.add(b);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    /**
     * Borrow a book:
     * - Enforces borrow limit (5 active borrows)
     * - Inserts into borrowings and marks book unavailable in a single transaction
     */
    public boolean borrowBook(String userId, String isbn) {
        String check = "SELECT COUNT(*) AS count FROM borrowings WHERE user_id = ? AND return_date IS NULL";
        String insert = "INSERT INTO borrowings(user_id, isbn, borrow_date) VALUES(?,?,?)";
        String update = "UPDATE books SET is_available = 0 WHERE isbn = ?";

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // check borrow count
                try (PreparedStatement psCheck = conn.prepareStatement(check)) {
                    psCheck.setString(1, userId);
                    try (ResultSet rs = psCheck.executeQuery()) {
                        if (rs.next() && rs.getInt("count") >= 5) {
                            JOptionPane.showMessageDialog(null, "❌ Borrow limit (5) reached for user " + userId);
                            conn.rollback();
                            return false;
                        }
                    }
                }

                // insert borrowing
                try (PreparedStatement ps = conn.prepareStatement(insert)) {
                    ps.setString(1, userId);
                    ps.setString(2, isbn);
                    ps.setString(3, Instant.now().toString());
                    ps.executeUpdate();
                }

                // update book availability
                try (PreparedStatement ps2 = conn.prepareStatement(update)) {
                    ps2.setString(1, isbn);
                    int updated = ps2.executeUpdate();
                    if (updated == 0) {
                        // No such book or update failed
                        conn.rollback();
                        return false;
                    }
                }

                conn.commit();
                return true;
            } catch (SQLException ex) {
                try {
                    conn.rollback();
                } catch (SQLException rbEx) {
                    rbEx.printStackTrace();
                }
                ex.printStackTrace();
                return false;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Return a book:
     * - Finds the latest active borrowing record (return_date IS NULL) for user+isbn
     * - Sets its return_date and marks book available in one transaction
     */
    public boolean returnBook(String userId, String isbn) {
        String find = "SELECT id FROM borrowings WHERE user_id = ? AND isbn = ? AND return_date IS NULL ORDER BY borrow_date DESC LIMIT 1";
        String updateBorrow = "UPDATE borrowings SET return_date = ? WHERE id = ?";
        String updateBook = "UPDATE books SET is_available = 1 WHERE isbn = ?";

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                Integer borrowId = null;
                try (PreparedStatement ps = conn.prepareStatement(find)) {
                    ps.setString(1, userId);
                    ps.setString(2, isbn);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            borrowId = rs.getInt("id");
                        }
                    }
                }

                if (borrowId == null) {
                    conn.rollback();
                    return false;
                }

                try (PreparedStatement ps2 = conn.prepareStatement(updateBorrow)) {
                    ps2.setString(1, Instant.now().toString());
                    ps2.setInt(2, borrowId);
                    ps2.executeUpdate();
                }

                try (PreparedStatement ps3 = conn.prepareStatement(updateBook)) {
                    ps3.setString(1, isbn);
                    ps3.executeUpdate();
                }

                conn.commit();
                return true;
            } catch (SQLException ex) {
                try {
                    conn.rollback();
                } catch (SQLException rbEx) {
                    rbEx.printStackTrace();
                }
                ex.printStackTrace();
                return false;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Populate provided lists with DB contents.
     * Note: borrowedBooks for each user is not filled here;
     * call getBorrowedBooksForUser if you need that.
     */
    public void populateLists(List<Book> booksOut, List<User> usersOut) {
        booksOut.clear();
        usersOut.clear();

        booksOut.addAll(getAllBooks());

        String sql = "SELECT * FROM users";
        try (Connection conn = getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                usersOut.add(new User(
                        rs.getString("user_id"),
                        rs.getString("name"),
                        rs.getString("contact")
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Utility to get currently borrowed (active) books for a user.
     */
    public List<Book> getBorrowedBooksForUser(String userId) {
        List<Book> list = new ArrayList<>();
        String sql = """
            SELECT b.isbn, b.title, b.author, b.genre, b.is_available
            FROM books b
            JOIN borrowings br ON b.isbn = br.isbn
            WHERE br.user_id = ? AND br.return_date IS NULL
            """;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Book b = new Book(
                            rs.getString("title"),
                            rs.getString("author"),
                            rs.getString("isbn"),
                            rs.getString("genre")
                    );
                    b.isAvailable = rs.getInt("is_available") == 1;
                    list.add(b);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }
}
