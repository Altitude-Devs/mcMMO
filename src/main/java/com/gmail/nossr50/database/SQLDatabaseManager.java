package com.gmail.nossr50.database;

import com.gmail.nossr50.api.exceptions.InvalidSkillException;
import com.gmail.nossr50.config.AdvancedConfig;
import com.gmail.nossr50.config.Config;
import com.gmail.nossr50.datatypes.MobHealthbarType;
import com.gmail.nossr50.datatypes.database.DatabaseType;
import com.gmail.nossr50.datatypes.database.PlayerStat;
import com.gmail.nossr50.datatypes.database.UpgradeType;
import com.gmail.nossr50.datatypes.party.ItemShareType;
import com.gmail.nossr50.datatypes.party.Party;
import com.gmail.nossr50.datatypes.party.PartyLeader;
import com.gmail.nossr50.datatypes.party.ShareMode;
import com.gmail.nossr50.datatypes.player.PlayerProfile;
import com.gmail.nossr50.datatypes.player.UniqueDataType;
import com.gmail.nossr50.datatypes.skills.PrimarySkillType;
import com.gmail.nossr50.datatypes.skills.SuperAbilityType;
import com.gmail.nossr50.mcMMO;
import com.gmail.nossr50.party.PartyManager;
import com.gmail.nossr50.runnables.database.UUIDUpdateAsyncTask;
import com.gmail.nossr50.util.Misc;
import org.apache.tomcat.jdbc.pool.DataSource;
import org.apache.tomcat.jdbc.pool.PoolProperties;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public final class SQLDatabaseManager implements DatabaseManager {
    private static final String ALL_QUERY_VERSION = "total";
    private final String tablePrefix = Config.getInstance().getMySQLTablePrefix();

    private final Map<UUID, Integer> cachedUserIDs = new HashMap<>();

    private DataSource miscPool;
    private DataSource loadPool;
    private DataSource savePool;

    private boolean debug = false;

    private final ReentrantLock massUpdateLock = new ReentrantLock();

    protected SQLDatabaseManager() {
        String connectionString = "jdbc:mysql://" + Config.getInstance().getMySQLServerName()
                + ":" + Config.getInstance().getMySQLServerPort() + "/" + Config.getInstance().getMySQLDatabaseName();

        if(Config.getInstance().getMySQLSSL())
            connectionString +=
                    "?verifyServerCertificate=false"+
                    "&useSSL=true"+
                    "&requireSSL=true";
        else
            connectionString+=
                    "?useSSL=false";

        try {
            // Force driver to load if not yet loaded
            Class.forName("com.mysql.jdbc.Driver");
        }
        catch (ClassNotFoundException e) {
            e.printStackTrace();
            return;
            //throw e; // aborts onEnable()  Riking if you want to do this, fully implement it.
        }

        debug = Config.getInstance().getMySQLDebug();


        PoolProperties poolProperties = new PoolProperties();
        poolProperties.setDriverClassName("com.mysql.jdbc.Driver");
        poolProperties.setUrl(connectionString);
        poolProperties.setUsername(Config.getInstance().getMySQLUserName());
        poolProperties.setPassword(Config.getInstance().getMySQLUserPassword());
        poolProperties.setMaxIdle(Config.getInstance().getMySQLMaxPoolSize(PoolIdentifier.MISC));
        poolProperties.setMaxActive(Config.getInstance().getMySQLMaxConnections(PoolIdentifier.MISC));
        poolProperties.setInitialSize(0);
        poolProperties.setMaxWait(-1);
        poolProperties.setRemoveAbandoned(true);
        poolProperties.setRemoveAbandonedTimeout(60);
        poolProperties.setTestOnBorrow(true);
        poolProperties.setValidationQuery("SELECT 1");
        poolProperties.setValidationInterval(30000);
        miscPool = new DataSource(poolProperties);
        poolProperties = new PoolProperties();
        poolProperties.setDriverClassName("com.mysql.jdbc.Driver");
        poolProperties.setUrl(connectionString);
        poolProperties.setUsername(Config.getInstance().getMySQLUserName());
        poolProperties.setPassword(Config.getInstance().getMySQLUserPassword());
        poolProperties.setInitialSize(0);
        poolProperties.setMaxIdle(Config.getInstance().getMySQLMaxPoolSize(PoolIdentifier.SAVE));
        poolProperties.setMaxActive(Config.getInstance().getMySQLMaxConnections(PoolIdentifier.SAVE));
        poolProperties.setMaxWait(-1);
        poolProperties.setRemoveAbandoned(true);
        poolProperties.setRemoveAbandonedTimeout(60);
        poolProperties.setTestOnBorrow(true);
        poolProperties.setValidationQuery("SELECT 1");
        poolProperties.setValidationInterval(30000);
        savePool = new DataSource(poolProperties);
        poolProperties = new PoolProperties();
        poolProperties.setDriverClassName("com.mysql.jdbc.Driver");
        poolProperties.setUrl(connectionString);
        poolProperties.setUsername(Config.getInstance().getMySQLUserName());
        poolProperties.setPassword(Config.getInstance().getMySQLUserPassword());
        poolProperties.setInitialSize(0);
        poolProperties.setMaxIdle(Config.getInstance().getMySQLMaxPoolSize(PoolIdentifier.LOAD));
        poolProperties.setMaxActive(Config.getInstance().getMySQLMaxConnections(PoolIdentifier.LOAD));
        poolProperties.setMaxWait(-1);
        poolProperties.setRemoveAbandoned(true);
        poolProperties.setRemoveAbandonedTimeout(60);
        poolProperties.setTestOnBorrow(true);
        poolProperties.setValidationQuery("SELECT 1");
        poolProperties.setValidationInterval(30000);
        loadPool = new DataSource(poolProperties);

        checkStructure();
    }

    public void purgePowerlessUsers() {
        massUpdateLock.lock();
        mcMMO.p.getLogger().info("Purging powerless users...");

        Connection connection = null;
        Statement statement = null;
        int purged = 0;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.createStatement();

            purged = statement.executeUpdate("DELETE FROM " + tablePrefix + "skills WHERE "
                    + "taming = 0 AND mining = 0 AND woodcutting = 0 AND repair = 0 "
                    + "AND unarmed = 0 AND herbalism = 0 AND excavation = 0 AND "
                    + "archery = 0 AND swords = 0 AND axes = 0 AND acrobatics = 0 "
                    + "AND fishing = 0 AND alchemy = 0;");

            statement.executeUpdate("DELETE FROM `" + tablePrefix + "experience` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "skills` `s` WHERE `" + tablePrefix + "experience`.`user_id` = `s`.`user_id`)");
            statement.executeUpdate("DELETE FROM `" + tablePrefix + "huds` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "skills` `s` WHERE `" + tablePrefix + "huds`.`user_id` = `s`.`user_id`)");
            statement.executeUpdate("DELETE FROM `" + tablePrefix + "cooldowns` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "skills` `s` WHERE `" + tablePrefix + "cooldowns`.`user_id` = `s`.`user_id`)");
            statement.executeUpdate("DELETE FROM `" + tablePrefix + "users` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "skills` `s` WHERE `" + tablePrefix + "users`.`id` = `s`.`user_id`)");
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
            tryClose(connection);
            massUpdateLock.unlock();
        }

        mcMMO.p.getLogger().info("Purged " + purged + " users from the database.");
    }

    public void purgeOldUsers() {
        massUpdateLock.lock();
        mcMMO.p.getLogger().info("Purging inactive users older than " + (PURGE_TIME / 2630000000L) + " months...");

        Connection connection = null;
        Statement statement = null;
        int purged = 0;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.createStatement();

            purged = statement.executeUpdate("DELETE FROM u, e, h, s, c USING " + tablePrefix + "users u " +
                    "JOIN " + tablePrefix + "experience e ON (u.id = e.user_id) " +
                    "JOIN " + tablePrefix + "huds h ON (u.id = h.user_id) " +
                    "JOIN " + tablePrefix + "skills s ON (u.id = s.user_id) " +
                    "JOIN " + tablePrefix + "cooldowns c ON (u.id = c.user_id) " +
                    "WHERE ((UNIX_TIMESTAMP() - lastlogin) > " + PURGE_TIME + ")");
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
            tryClose(connection);
            massUpdateLock.unlock();
        }

        mcMMO.p.getLogger().info("Purged " + purged + " users from the database.");
    }

    public boolean removeUser(String playerName, UUID uuid) {
        boolean success = false;
        Connection connection = null;
        PreparedStatement statement = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.prepareStatement("DELETE FROM u, e, h, s, c " +
                    "USING " + tablePrefix + "users u " +
                    "JOIN " + tablePrefix + "experience e ON (u.id = e.user_id) " +
                    "JOIN " + tablePrefix + "huds h ON (u.id = h.user_id) " +
                    "JOIN " + tablePrefix + "skills s ON (u.id = s.user_id) " +
                    "JOIN " + tablePrefix + "cooldowns c ON (u.id = c.user_id) " +
                    "WHERE u.user = ?");

            statement.setString(1, playerName);

            success = statement.executeUpdate() != 0;
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
            tryClose(connection);
        }

        if (success) {
            if(uuid != null)
                cleanupUser(uuid);

            Misc.profileCleanup(playerName);
        }

        return success;
    }

    public void cleanupUser(UUID uuid) {
        cachedUserIDs.remove(uuid);
    }

    public boolean saveUser(PlayerProfile profile) {
        boolean success = true;
        PreparedStatement statement = null;
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.SAVE);

            int id = getUserID(connection, profile.getPlayerName(), profile.getUniqueId());

            if (id == -1) {
                id = newUser(connection, profile.getPlayerName(), profile.getUniqueId());
                if (id == -1) {
                    mcMMO.p.getLogger().severe("Failed to create new account for " + profile.getPlayerName());
                    return false;
                }
            }

            statement = connection.prepareStatement("UPDATE " + tablePrefix + "users SET lastlogin = UNIX_TIMESTAMP() WHERE id = ?");
            statement.setInt(1, id);
            success &= (statement.executeUpdate() != 0);
            statement.close();
            if (!success) {
                mcMMO.p.getLogger().severe("Failed to update last login for " + profile.getPlayerName());
                return false;
            }

            statement = connection.prepareStatement("UPDATE " + tablePrefix + "skills SET "
                    + " taming = ?, mining = ?, repair = ?, woodcutting = ?"
                    + ", unarmed = ?, herbalism = ?, excavation = ?"
                    + ", archery = ?, swords = ?, axes = ?, acrobatics = ?"
                    + ", fishing = ?, alchemy = ?, total = ? WHERE user_id = ?");
            statement.setInt(1, profile.getSkillLevel(PrimarySkillType.TAMING));
            statement.setInt(2, profile.getSkillLevel(PrimarySkillType.MINING));
            statement.setInt(3, profile.getSkillLevel(PrimarySkillType.REPAIR));
            statement.setInt(4, profile.getSkillLevel(PrimarySkillType.WOODCUTTING));
            statement.setInt(5, profile.getSkillLevel(PrimarySkillType.UNARMED));
            statement.setInt(6, profile.getSkillLevel(PrimarySkillType.HERBALISM));
            statement.setInt(7, profile.getSkillLevel(PrimarySkillType.EXCAVATION));
            statement.setInt(8, profile.getSkillLevel(PrimarySkillType.ARCHERY));
            statement.setInt(9, profile.getSkillLevel(PrimarySkillType.SWORDS));
            statement.setInt(10, profile.getSkillLevel(PrimarySkillType.AXES));
            statement.setInt(11, profile.getSkillLevel(PrimarySkillType.ACROBATICS));
            statement.setInt(12, profile.getSkillLevel(PrimarySkillType.FISHING));
            statement.setInt(13, profile.getSkillLevel(PrimarySkillType.ALCHEMY));
            int total = 0;
            for (PrimarySkillType primarySkillType : PrimarySkillType.NON_CHILD_SKILLS)
                total += profile.getSkillLevel(primarySkillType);
            statement.setInt(14, total);
            statement.setInt(15, id);
            success &= (statement.executeUpdate() != 0);
            statement.close();
            if (!success) {
                mcMMO.p.getLogger().severe("Failed to update skills for " + profile.getPlayerName());
                return false;
            }

            statement = connection.prepareStatement("UPDATE " + tablePrefix + "experience SET "
                    + " taming = ?, mining = ?, repair = ?, woodcutting = ?"
                    + ", unarmed = ?, herbalism = ?, excavation = ?"
                    + ", archery = ?, swords = ?, axes = ?, acrobatics = ?"
                    + ", fishing = ?, alchemy = ? WHERE user_id = ?");
            statement.setInt(1, profile.getSkillXpLevel(PrimarySkillType.TAMING));
            statement.setInt(2, profile.getSkillXpLevel(PrimarySkillType.MINING));
            statement.setInt(3, profile.getSkillXpLevel(PrimarySkillType.REPAIR));
            statement.setInt(4, profile.getSkillXpLevel(PrimarySkillType.WOODCUTTING));
            statement.setInt(5, profile.getSkillXpLevel(PrimarySkillType.UNARMED));
            statement.setInt(6, profile.getSkillXpLevel(PrimarySkillType.HERBALISM));
            statement.setInt(7, profile.getSkillXpLevel(PrimarySkillType.EXCAVATION));
            statement.setInt(8, profile.getSkillXpLevel(PrimarySkillType.ARCHERY));
            statement.setInt(9, profile.getSkillXpLevel(PrimarySkillType.SWORDS));
            statement.setInt(10, profile.getSkillXpLevel(PrimarySkillType.AXES));
            statement.setInt(11, profile.getSkillXpLevel(PrimarySkillType.ACROBATICS));
            statement.setInt(12, profile.getSkillXpLevel(PrimarySkillType.FISHING));
            statement.setInt(13, profile.getSkillXpLevel(PrimarySkillType.ALCHEMY));
            statement.setInt(14, id);
            success &= (statement.executeUpdate() != 0);
            statement.close();
            if (!success) {
                mcMMO.p.getLogger().severe("Failed to update experience for " + profile.getPlayerName());
                return false;
            }

            statement = connection.prepareStatement("UPDATE " + tablePrefix + "cooldowns SET "
                    + "  mining = ?, woodcutting = ?, unarmed = ?"
                    + ", herbalism = ?, excavation = ?, swords = ?"
                    + ", axes = ?, blast_mining = ?, chimaera_wing = ? WHERE user_id = ?");
            statement.setLong(1, profile.getAbilityDATS(SuperAbilityType.SUPER_BREAKER));
            statement.setLong(2, profile.getAbilityDATS(SuperAbilityType.TREE_FELLER));
            statement.setLong(3, profile.getAbilityDATS(SuperAbilityType.BERSERK));
            statement.setLong(4, profile.getAbilityDATS(SuperAbilityType.GREEN_TERRA));
            statement.setLong(5, profile.getAbilityDATS(SuperAbilityType.GIGA_DRILL_BREAKER));
            statement.setLong(6, profile.getAbilityDATS(SuperAbilityType.SERRATED_STRIKES));
            statement.setLong(7, profile.getAbilityDATS(SuperAbilityType.SKULL_SPLITTER));
            statement.setLong(8, profile.getAbilityDATS(SuperAbilityType.BLAST_MINING));
            statement.setLong(9, profile.getUniqueData(UniqueDataType.CHIMAERA_WING_DATS));
            statement.setInt(10, id);
            success = (statement.executeUpdate() != 0);
            statement.close();
            if (!success) {
                mcMMO.p.getLogger().severe("Failed to update cooldowns for " + profile.getPlayerName());
                return false;
            }

            statement = connection.prepareStatement("UPDATE " + tablePrefix + "huds SET mobhealthbar = ?, scoreboardtips = ? WHERE user_id = ?");
            statement.setString(1, profile.getMobHealthbarType() == null ? Config.getInstance().getMobHealthbarDefault().name() : profile.getMobHealthbarType().name());
            statement.setInt(2, profile.getScoreboardTipsShown());
            statement.setInt(3, id);
            success = (statement.executeUpdate() != 0);
            statement.close();
            if (!success) {
                mcMMO.p.getLogger().severe("Failed to update hud settings for " + profile.getPlayerName());
                return false;
            }
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
            tryClose(connection);
        }

        return success;
    }

    public @NotNull List<PlayerStat> readLeaderboard(@Nullable PrimarySkillType skill, int pageNumber, int statsPerPage) throws InvalidSkillException {
        List<PlayerStat> stats = new ArrayList<>();

        //Fix for a plugin that people are using that is throwing SQL errors
        if(skill != null && skill.isChildSkill()) {
            mcMMO.p.getLogger().severe("A plugin hooking into mcMMO is being naughty with our database commands, update all plugins that hook into mcMMO and contact their devs!");
            throw new InvalidSkillException("A plugin hooking into mcMMO that you are using is attempting to read leaderboard skills for child skills, child skills do not have leaderboards! This is NOT an mcMMO error!");
        }


        String query = skill == null ? ALL_QUERY_VERSION : skill.name().toLowerCase(Locale.ENGLISH);
        ResultSet resultSet = null;
        PreparedStatement statement = null;
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.prepareStatement("SELECT " + query + ", user FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON (user_id = id) WHERE " + query + " > 0 AND NOT user = '\\_INVALID\\_OLD\\_USERNAME\\_' ORDER BY " + query + " DESC, user LIMIT ?, ?");
            statement.setInt(1, (pageNumber * statsPerPage) - statsPerPage);
            statement.setInt(2, statsPerPage);
            resultSet = statement.executeQuery();

            while (resultSet.next()) {
                ArrayList<String> column = new ArrayList<>();

                for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++) {
                    column.add(resultSet.getString(i));
                }

                stats.add(new PlayerStat(column.get(1), Integer.parseInt(column.get(0))));
            }
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(connection);
        }

        return stats;
    }

    public Map<PrimarySkillType, Integer> readRank(String playerName) {
        Map<PrimarySkillType, Integer> skills = new HashMap<>();

        ResultSet resultSet = null;
        PreparedStatement statement = null;
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            for (PrimarySkillType primarySkillType : PrimarySkillType.NON_CHILD_SKILLS) {
                String skillName = primarySkillType.name().toLowerCase(Locale.ENGLISH);
                // Get count of all users with higher skill level than player
                String sql = "SELECT COUNT(*) AS 'rank' FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id WHERE " + skillName + " > 0 " +
                        "AND " + skillName + " > (SELECT " + skillName + " FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id " +
                        "WHERE user = ?)";

                statement = connection.prepareStatement(sql);
                statement.setString(1, playerName);
                resultSet = statement.executeQuery();

                resultSet.next();

                int rank = resultSet.getInt("rank");

                // Ties are settled by alphabetical order
                sql = "SELECT user, " + skillName + " FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id WHERE " + skillName + " > 0 " +
                        "AND " + skillName + " = (SELECT " + skillName + " FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id " +
                        "WHERE user = '" + playerName + "') ORDER BY user";

                resultSet.close();
                statement.close();

                statement = connection.prepareStatement(sql);
                resultSet = statement.executeQuery();

                while (resultSet.next()) {
                    if (resultSet.getString("user").equalsIgnoreCase(playerName)) {
                        skills.put(primarySkillType, rank + resultSet.getRow());
                        break;
                    }
                }

                resultSet.close();
                statement.close();
            }

            String sql = "SELECT COUNT(*) AS 'rank' FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id " +
                    "WHERE " + ALL_QUERY_VERSION + " > 0 " +
                    "AND " + ALL_QUERY_VERSION + " > " +
                    "(SELECT " + ALL_QUERY_VERSION + " " +
                    "FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id WHERE user = ?)";

            statement = connection.prepareStatement(sql);
            statement.setString(1, playerName);
            resultSet = statement.executeQuery();

            resultSet.next();

            int rank = resultSet.getInt("rank");

            resultSet.close();
            statement.close();

            sql = "SELECT user, " + ALL_QUERY_VERSION + " " +
                    "FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id " +
                    "WHERE " + ALL_QUERY_VERSION + " > 0 " +
                    "AND " + ALL_QUERY_VERSION + " = " +
                    "(SELECT " + ALL_QUERY_VERSION + " " +
                    "FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id WHERE user = ?) ORDER BY user";

            statement = connection.prepareStatement(sql);
            statement.setString(1, playerName);
            resultSet = statement.executeQuery();

            while (resultSet.next()) {
                if (resultSet.getString("user").equalsIgnoreCase(playerName)) {
                    skills.put(null, rank + resultSet.getRow());
                    break;
                }
            }

            resultSet.close();
            statement.close();
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(connection);
        }

        return skills;
    }

    public void newUser(String playerName, UUID uuid) {
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            newUser(connection, playerName, uuid);
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(connection);
        }
    }

    private int newUser(Connection connection, String playerName, UUID uuid) {
        ResultSet resultSet = null;
        PreparedStatement statement = null;

        try {
            statement = connection.prepareStatement(
                    "UPDATE `" + tablePrefix + "users` "
                            + "SET user = ? "
                            + "WHERE user = ?");
            statement.setString(1, "_INVALID_OLD_USERNAME_");
            statement.setString(2, playerName);
            statement.executeUpdate();
            statement.close();
            statement = connection.prepareStatement("INSERT INTO " + tablePrefix + "users (user, uuid, lastlogin) VALUES (?, ?, UNIX_TIMESTAMP())", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, playerName);
            statement.setString(2, uuid != null ? uuid.toString() : null);
            statement.executeUpdate();

            resultSet = statement.getGeneratedKeys();

            if (!resultSet.next()) {
                mcMMO.p.getLogger().severe("Unable to create new user account in DB");
                return -1;
            }

            writeMissingRows(connection, resultSet.getInt(1));
            return resultSet.getInt(1);
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
        }
        return -1;
    }

    @Deprecated
    public PlayerProfile loadPlayerProfile(String playerName, boolean create) {
        return loadPlayerProfile(playerName, null, false, true);
    }

    public PlayerProfile loadPlayerProfile(UUID uuid) {
        return loadPlayerProfile("", uuid, false, true);
    }

    public PlayerProfile loadPlayerProfile(String playerName, UUID uuid, boolean create) {
        return loadPlayerProfile(playerName, uuid, create, true);
    }

    private PlayerProfile loadPlayerProfile(String playerName, UUID uuid, boolean create, boolean retry) {
        PreparedStatement statement = null;
        Connection connection = null;
        ResultSet resultSet = null;

        try {
            connection = getConnection(PoolIdentifier.LOAD);
            int id = getUserID(connection, playerName, uuid);

            if (id == -1) {
                // There is no such user
                if (create) {
                    id = newUser(connection, playerName, uuid);
                    create = false;
                    if (id == -1) {
                        return new PlayerProfile(playerName, false);
                    }
                } else {
                    return new PlayerProfile(playerName, false);
                }
            }
            // There is such a user
            writeMissingRows(connection, id);

            statement = connection.prepareStatement(
                    "SELECT "
                            + "s.taming, s.mining, s.repair, s.woodcutting, s.unarmed, s.herbalism, s.excavation, s.archery, s.swords, s.axes, s.acrobatics, s.fishing, s.alchemy, "
                            + "e.taming, e.mining, e.repair, e.woodcutting, e.unarmed, e.herbalism, e.excavation, e.archery, e.swords, e.axes, e.acrobatics, e.fishing, e.alchemy, "
                            + "c.taming, c.mining, c.repair, c.woodcutting, c.unarmed, c.herbalism, c.excavation, c.archery, c.swords, c.axes, c.acrobatics, c.blast_mining, c.chimaera_wing, "
                            + "h.mobhealthbar, h.scoreboardtips, u.uuid, u.user "
                            + "FROM " + tablePrefix + "users u "
                            + "JOIN " + tablePrefix + "skills s ON (u.id = s.user_id) "
                            + "JOIN " + tablePrefix + "experience e ON (u.id = e.user_id) "
                            + "JOIN " + tablePrefix + "cooldowns c ON (u.id = c.user_id) "
                            + "JOIN " + tablePrefix + "huds h ON (u.id = h.user_id) "
                            + "WHERE u.id = ?");
            statement.setInt(1, id);

            resultSet = statement.executeQuery();

            if (resultSet.next()) {
                try {
                    PlayerProfile profile = loadFromResult(playerName, resultSet);
                    String name = resultSet.getString(42); // TODO: Magic Number, make sure it stays updated
                    resultSet.close();
                    statement.close();

                    if (!playerName.isEmpty() && !playerName.equalsIgnoreCase(name) && uuid != null) {
                        statement = connection.prepareStatement(
                                "UPDATE `" + tablePrefix + "users` "
                                        + "SET user = ? "
                                        + "WHERE user = ?");
                        statement.setString(1, "_INVALID_OLD_USERNAME_");
                        statement.setString(2, name);
                        statement.executeUpdate();
                        statement.close();
                        statement = connection.prepareStatement(
                                "UPDATE `" + tablePrefix + "users` "
                                        + "SET user = ?, uuid = ? "
                                        + "WHERE id = ?");
                        statement.setString(1, playerName);
                        statement.setString(2, uuid.toString());
                        statement.setInt(3, id);
                        statement.executeUpdate();
                        statement.close();
                    }

                    return profile;
                }
                catch (SQLException e) {
                    printErrors(e);
                }
            }
            resultSet.close();
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(connection);
        }

        // Problem, nothing was returned

        // return unloaded profile
        if (!retry) {
            return new PlayerProfile(playerName, false);
        }

        // Retry, and abort on re-failure
        return loadPlayerProfile(playerName, uuid, create, false);
    }

    public void convertUsers(DatabaseManager destination) {
        PreparedStatement statement = null;
        Connection connection = null;
        ResultSet resultSet = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.prepareStatement(
                    "SELECT "
                            + "s.taming, s.mining, s.repair, s.woodcutting, s.unarmed, s.herbalism, s.excavation, s.archery, s.swords, s.axes, s.acrobatics, s.fishing, s.alchemy, "
                            + "e.taming, e.mining, e.repair, e.woodcutting, e.unarmed, e.herbalism, e.excavation, e.archery, e.swords, e.axes, e.acrobatics, e.fishing, e.alchemy, "
                            + "c.taming, c.mining, c.repair, c.woodcutting, c.unarmed, c.herbalism, c.excavation, c.archery, c.swords, c.axes, c.acrobatics, c.blast_mining, c.chimaera_wing, "
                            + "h.mobhealthbar, h.scoreboardtips, u.uuid "
                            + "FROM " + tablePrefix + "users u "
                            + "JOIN " + tablePrefix + "skills s ON (u.id = s.user_id) "
                            + "JOIN " + tablePrefix + "experience e ON (u.id = e.user_id) "
                            + "JOIN " + tablePrefix + "cooldowns c ON (u.id = c.user_id) "
                            + "JOIN " + tablePrefix + "huds h ON (u.id = h.user_id) "
                            + "WHERE u.user = ?");
            List<String> usernames = getStoredUsers();
            int convertedUsers = 0;
            long startMillis = System.currentTimeMillis();
            for (String playerName : usernames) {
                statement.setString(1, playerName);
                try {
                    resultSet = statement.executeQuery();
                    resultSet.next();
                    destination.saveUser(loadFromResult(playerName, resultSet));
                    resultSet.close();
                }
                catch (SQLException e) {
                    printErrors(e);
                    // Ignore
                }
                convertedUsers++;
                Misc.printProgress(convertedUsers, progressInterval, startMillis);
            }
        }
        catch (SQLException e) {
            printErrors(e);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(connection);
        }

    }

    public boolean saveUserUUID(String userName, UUID uuid) {
        PreparedStatement statement = null;
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.prepareStatement(
                    "UPDATE `" + tablePrefix + "users` SET "
                            + "  uuid = ? WHERE user = ?");
            statement.setString(1, uuid.toString());
            statement.setString(2, userName);
            statement.execute();
            return true;
        }
        catch (SQLException ex) {
            printErrors(ex);
            return false;
        }
        finally {
            tryClose(statement);
            tryClose(connection);
        }
    }

    public boolean saveUserUUIDs(Map<String, UUID> fetchedUUIDs) {
        PreparedStatement statement = null;
        int count = 0;

        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.prepareStatement("UPDATE " + tablePrefix + "users SET uuid = ? WHERE user = ?");

            for (Map.Entry<String, UUID> entry : fetchedUUIDs.entrySet()) {
                statement.setString(1, entry.getValue().toString());
                statement.setString(2, entry.getKey());

                statement.addBatch();

                count++;

                if ((count % 500) == 0) {
                    statement.executeBatch();
                    count = 0;
                }
            }

            if (count != 0) {
                statement.executeBatch();
            }

            return true;
        }
        catch (SQLException ex) {
            printErrors(ex);
            return false;
        }
        finally {
            tryClose(statement);
            tryClose(connection);
        }
    }

    /**
     * Loads all parties and stores them in provided List
     * @param parties List to store all parties into
     */
    public void loadParties(List<Party> parties) {
        Connection connection = null;
        PreparedStatement statement = null;
        PreparedStatement statementAllies = null;
        ResultSet resultSet = null;
        ResultSet resultSetAllies = null;

        try{
            connection = getConnection(PoolIdentifier.LOAD);
            statement = connection.prepareStatement("SELECT * FROM " + tablePrefix + "parties");
            resultSet = statement.executeQuery();

            while (resultSet.next()){
                Party party = getParty(resultSet, connection);

                parties.add(party);
            }

            statementAllies = connection.prepareStatement(
                    "SELECT A.party_name AS party_name, B.party_name AS ally_name "
                            + "FROM `" + tablePrefix + "parties` A, `" + tablePrefix + "parties` B "
                            + "WHERE A.ally IS NOT NULL "
                            + "AND A.ally = B.id");
            resultSetAllies = statementAllies.executeQuery();

            while (resultSetAllies.next()){
                Party party = PartyManager.getParty(resultSetAllies.getString("party_name"));
                if (party != null) {
                    party.setAlly(PartyManager.getParty(resultSetAllies.getString("ally_name")));
                } else {
                    mcMMO.p.getLogger().severe("Could not find party from database in loaded parties.");
                }
            }

        } catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
            tryClose(resultSetAllies);
            tryClose(statement);
            tryClose(statementAllies);
            tryClose(connection);
        }
    }

    /**
     * Returns a Party object
     * @param resultSetGetParty ResultSet for a party
     * @param connection Connection to get users belonging to the party with
     * @return Party object
     */
    private Party getParty(@NotNull ResultSet resultSetGetParty, @NotNull Connection connection){

        Party party = null;

        PreparedStatement statementGetUsers = null;
        ResultSet resultSetGetUsers = null;
        try{
            party = new Party(resultSetGetParty.getString("party_name"));

            PartyLeader partyLeader = getPartyLeaderById(resultSetGetParty.getString("owner_id"));

            party.setLeader(partyLeader);
            String password = resultSetGetParty.getString("password");
            if (password != null){
                party.setPassword(password);
            }
            party.setLocked(resultSetGetParty.getInt("locked") == 1);
            party.setLevel(resultSetGetParty.getInt("level"));
            party.setXp(resultSetGetParty.getFloat("xp"));

            party.setXpShareMode(ShareMode.getShareMode(resultSetGetParty.getString("exp_share_mode")));
            party.setItemShareMode(ShareMode.getShareMode(resultSetGetParty.getString("item_share_mode")));

            for (ItemShareType itemShareType : ItemShareType.values()) {
                party.setSharingDrops(itemShareType, resultSetGetParty.getInt(itemShareType.toString()) == 1);
            }

            statementGetUsers = connection.prepareStatement(
                    "SELECT `" + tablePrefix + "users`.`uuid`, `" + tablePrefix +  "users`.`user` "
                            + "FROM `" + tablePrefix + "party_users` "
                            + "INNER JOIN `" + tablePrefix + "users` "
                            + "ON `" + tablePrefix + "party_users`.`user_id` = `" + tablePrefix + "users`.`id` "
                            + "WHERE `" + tablePrefix + "party_users`.`party_id` = ?" );//TODO test this
            statementGetUsers.setInt(1, resultSetGetParty.getInt("id"));
            resultSetGetUsers = statementGetUsers.executeQuery();

            LinkedHashMap<UUID, String> members = party.getMembers();

            if (resultSetGetUsers.next()){
                members.put(UUID.fromString(resultSetGetUsers.getString("uuid")), resultSetGetUsers.getString("user"));
            } else {
                deleteParty(party);
            }

            while (resultSetGetUsers.next()){
                members.put(UUID.fromString(resultSetGetUsers.getString("uuid")), resultSetGetUsers.getString("user"));
            }

        } catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statementGetUsers);
            tryClose(resultSetGetUsers);
        }

        return party;
    }

    /**
     * Returns a PartyLeader object from the id of a party leader
     * @param owner_id Id of the owner of the party
     * @return PartyLeader object for the party leader with the specified id
     */
    private PartyLeader getPartyLeaderById(String owner_id) {

        PartyLeader partyLeader = null;
        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet resultSet = null;

        try {
            connection = getConnection(PoolIdentifier.LOAD);
            statement = connection.prepareStatement("SELECT uuid, user FROM `" + tablePrefix + "users` WHERE id = ?");

            statement.setString(1, owner_id);

            resultSet = statement.executeQuery();
            partyLeader = new PartyLeader(UUID.fromString(resultSet.getString("uuid")), resultSet.getString("user"));
        } catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(connection);
        }

        return partyLeader;
    }

    /**
     * Checks if a party is in the database
     * @param partyName Name of party to check for
     * @return true if the party exists
     */
    public boolean partyExists(String partyName){

        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet resultSet = null;

        try{
            connection = getConnection(PoolIdentifier.LOAD);

            statement = connection.prepareStatement("SELECT 1 FROM " + tablePrefix + "parties WHERE party_name = ?");
            statement.setString(1, partyName);
            resultSet = statement.executeQuery();

            if (resultSet.next()){
                return true;
            }

        } catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
            tryClose(resultSet);
            tryClose(connection);
        }

        return false;
    }

    /**
     * Removes a player from a party
     * @param player Player to remove
     * @param partyName Name of party to remove the player from
     */
    public void removeFromParty(OfflinePlayer player, String partyName) {
        Connection connection = null;
        PreparedStatement statement = null;

        try{
            connection = getConnection(PoolIdentifier.SAVE);

            statement = connection.prepareStatement("DELETE `" + tablePrefix + "party_users` FROM `" + tablePrefix + "party_users`"
                    + "INNER JOIN `" + tablePrefix + "users` ON `" + tablePrefix + "users`.`id`=`" + tablePrefix + "party_users`.`user_id`"
                    + "INNER JOIN `" + tablePrefix + "parties` ON `" + tablePrefix + "parties`.`id`=`" + tablePrefix + "party_users`.`party_id`"
                    + "WHERE `" + tablePrefix + "users.`user` = ?) "
                    + "AND `" + tablePrefix + "parties`.`party_name` = ?)");
            statement.setString(1, player.getName());
            statement.setString(2, partyName);
            statement.execute();

        } catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
            tryClose(connection);
        }
    }

    /**
     * Deletes a party from the database
     * @param party Party to delete
     */
    public void deleteParty(Party party) {
        Connection connection = null;
        PreparedStatement statement = null;

        if (party.getAlly() != null){
            disbandAlliance(party.getName(), party.getAlly().getName());
        }

        try{
            connection = getConnection(PoolIdentifier.SAVE);

            statement = connection.prepareStatement("DELETE FROM `" + tablePrefix + "parties` WHERE party_name = ?");
            statement.setString(1, party.getName());
            statement.execute();

        } catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
            tryClose(connection);
        }
    }

    /**
     * Set's the leader of a party
     * @param player Player to make owner of a party
     * @param partyName Party to make player the owner of
     */
    public void setPartyLeader(OfflinePlayer player, String partyName) {
        Connection connection = null;
        PreparedStatement statement = null;

        int userId = getPartyUserId(player.getName());

        if (userId == -1){
            userId = addUserToPartyGetResult(player.getName(), player.getUniqueId(), partyName);
        }

        try{
            connection = getConnection(PoolIdentifier.SAVE);

            statement = connection.prepareStatement("UPDATE `" + tablePrefix + "parties` "
                    + "SET owner_id = ? "
                    + "WHERE party_name = ?");
            statement.setInt(1, userId);
            statement.setString(2, partyName);
            statement.execute();

        } catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
            tryClose(connection);
        }
    }

    /**
     * Get's user id belonging to specified party member
     * @param userName Name of the party member to get the id for
     * @return user id if it was found -1 if it wasn't found
     */
    private int getPartyUserId(String userName) {
        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet resultSet = null;

        try{
            connection = getConnection(PoolIdentifier.LOAD);

            statement = connection.prepareStatement("SELECT user_id FROM `" + tablePrefix + "party_users` "
                    + "INNER JOIN `" + tablePrefix + "users` ON `" + tablePrefix + "users`.`id`=`" + tablePrefix + "party_users`.`user_id"
                    + "WHERE `" + tablePrefix + "users`.`user` = ?");
            statement.setString(1, userName);
            resultSet = statement.executeQuery();

            if (resultSet.next()){
                return resultSet.getInt("user_id");
            }

        } catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
            tryClose(resultSet);
            tryClose(connection);
        }

        return -1;
    }

    /**
     * Sets alliance for the two specified parties
     * @param partyName Party to set the alliance for
     * @param allyName Party to alliance with
     */
    public void setAllies(String partyName, String allyName){
        HashMap<String, Integer> partyIds = getPartyIds(Arrays.asList(partyName, allyName));

        if (partyIds.size() == 2) {
            Connection connection = null;
            PreparedStatement statement = null;

            try{
                connection = getConnection(PoolIdentifier.SAVE);
                statement = connection.prepareStatement("UPDATE `" + tablePrefix + "parties` "
                        + "SET ally = ? "
                        + "WHERE party_id = ?)");
                statement.setInt(1, partyIds.get(allyName));
                statement.setInt(2, partyIds.get(partyName));
                statement.executeUpdate();

                statement = connection.prepareStatement("UPDATE `" + tablePrefix + "parties` "
                        + "SET ally = ? "
                        + "WHERE party_id = ?)");
                statement.setInt(1, partyIds.get(partyName));
                statement.setInt(2, partyIds.get(allyName));
                statement.executeUpdate();

            } catch (SQLException ex) {
                printErrors(ex);
            }
            finally {
                tryClose(statement);
                tryClose(connection);
            }
        }
    }

    /**
     * Sets ally to null for both parties
     * @param partyName Party that wants to disband alliance
     * @param allyName Party to disband alliance with
     */
    public void disbandAlliance(String partyName, String allyName) {
        Connection connection = null;
        PreparedStatement statement = null;

        try{
            connection = getConnection(PoolIdentifier.SAVE);
            statement = connection.prepareStatement("UPDATE `" + tablePrefix + "parties` "
                    + "SET ally = NULL "
                    + "WHERE party_id IN (?, ?)");
            statement.setString(1, partyName);
            statement.setString(2, allyName);
            statement.execute();

        } catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
            tryClose(connection);
        }
    }

    private Party getParty(@NotNull String partyName){ //TODO check if needed?

        Party party = null;

        Connection connection = null;
        PreparedStatement statementGetParty = null;
        PreparedStatement statementGetAlly = null;
        ResultSet resultSetGetParty = null;
        ResultSet resultSetGetAlly = null;

        try{
            connection = getConnection(PoolIdentifier.LOAD);

            statementGetParty = connection.prepareStatement("SELECT * FROM " + tablePrefix + "parties WHERE party_name = ?");
            statementGetParty.setString(1, partyName);
            resultSetGetParty = statementGetParty.executeQuery();

            if (!resultSetGetParty.next()){
                //TODO Error empty party
                return null;
            }

            party = getParty(resultSetGetParty, connection);

            statementGetAlly = connection.prepareStatement(
                    "SELECT A.party_name AS party_name, B.party_name AS ally_name "
                            + "FROM `" + tablePrefix + "parties` A, `" + tablePrefix + "parties` B "
                            + "WHERE party_name = ?"
                            + "AND A.ally = B.id");
            statementGetAlly.setString(1, partyName);
            resultSetGetAlly = statementGetAlly.executeQuery();

            if (resultSetGetAlly.next()){
                party.setAlly(PartyManager.getParty(resultSetGetAlly.getString("ally")));
            }

        } catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statementGetParty);
            tryClose(statementGetAlly);
            tryClose(resultSetGetParty);
            tryClose(resultSetGetAlly);
            tryClose(connection);
        }

        return party;
    }

    /**
     * Saves a party
     * @param party Party to save
     * @return True if saving was successful
     */
    public boolean saveParty(Party party){
        PreparedStatement statement = null;
        Connection connection = null;
        ResultSet resultSet = null;

        try{
            connection = getConnection(PoolIdentifier.LOAD); //We're only loading data here, the saving happens in separate functions with their own connections
            statement = connection.prepareStatement("SELECT party_name FROM `" + tablePrefix + "parties` WHERE party_name = ?");
            statement.setString(1, party.getName());
            resultSet = statement.executeQuery();

            if (resultSet.next()){
                mcMMO.p.getLogger().warning("There is already a party called " + party.getName() + ".");
                return false;
            }

            PartyLeader leader = party.getLeader();
            int userID = getUserID(connection, leader.getPlayerName(), leader.getUniqueId());

            if (userID == -1){
                mcMMO.p.getLogger().warning("Unable to save party " + party.getName() + " due to the parties leader not being in the database.");
                return false;
            }

            int id = storeParty(party, userID);

            if (id != -1) {
                addUsersToParty(party.getMembers(), id);
            } else {
                return false;
            }

        } catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(connection);
        }

        return true;
    }

    /**
     * This saves all parties in the given list, this runs a lot of queries and should only be used to import the parties.yml file into a database.
     * Normally every change to a party other than xp is saved right away. Xp get's saved at intervals and on shutdown using TODO add function name here
     * @param parties Parties to be saved
     */
    public void saveParties(Collection<? extends Party> parties) {
        PreparedStatement statement = null;
        Connection connection = null;
        ResultSet resultSet = null;

        try{
            connection = getConnection(PoolIdentifier.LOAD); //We're only loading data here, the saving happens in separate functions with their own connections
            statement = connection.prepareStatement("SELECT party_name FROM `" + tablePrefix + "parties`");
            resultSet = statement.executeQuery();

            ArrayList<String> partyList = new ArrayList<>();

            while (resultSet.next()){
                partyList.add(resultSet.getString("party_name"));
            }

            for (Party party : parties){
                mcMMO.p.getLogger().info("Saving party: " + party.getName() + ".");

                if (partyList.contains(party.getName())){
                    mcMMO.p.getLogger().warning("There is already a party called " + party.getName() + " in the database so we won't add this to the database.");

                } else {
                    PartyLeader leader = party.getLeader();
                    int userID = getUserID(connection, leader.getPlayerName(), leader.getUniqueId());
                    if (userID == -1){
                        mcMMO.p.getLogger().warning("Unable to save party " + party.getName() + " due to the parties leader not being in the database.");
                        continue;
                    }
                    int id = storeParty(party, userID);
                    if (id != -1) {
                        addUsersToParty(party.getMembers(), id);
                    } else {
                        mcMMO.p.getLogger().severe("Unable to save party " + party.getName() + ".");
                    }
                }
            }

        } catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(connection);
        }
    }

    /**
     * Inserts party into the database (assumes this party isn't already in the database, check that before calling this function
     * @param party Party to be added to the database
     * @param leaderId userId belonging to the party leader
     * @return The id of the party after it's stored in the database on success and -1 on failure
     */
    private int storeParty(Party party, int leaderId) {
        PreparedStatement statement = null;
        Connection connection = null;

        try{
            connection = getConnection(PoolIdentifier.SAVE);

            statement = connection.prepareStatement("INSERT INTO `" + tablePrefix + "parties` "
                    + "(`party_name`, `owner_id`, `password`, `locked`, `level`, `xp`, `exp_share_mode`, `item_share_mode`, "
                    + "`LOOT`, `MINING`, `HERBALISM`, `WOODCUTTING`, `MISC`) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?);");

            statement.setString(1, party.getName());
            statement.setInt(2, leaderId);
            statement.setString(3, party.getPassword());
            statement.setInt(4, party.isLocked() ? 1 : 0);
            statement.setInt(5, party.getLevel());
            statement.setFloat(6, party.getXp());
            statement.setString(7, party.getXpShareMode().name());
            statement.setString(8, party.getItemShareMode().name());
            statement.setInt(9, party.sharingDrops(ItemShareType.LOOT) ? 1 : 0);
            statement.setInt(10, party.sharingDrops(ItemShareType.MINING) ? 1 : 0);
            statement.setInt(11, party.sharingDrops(ItemShareType.HERBALISM) ? 1 : 0);
            statement.setInt(12, party.sharingDrops(ItemShareType.WOODCUTTING) ? 1 : 0);
            statement.setInt(13, party.sharingDrops(ItemShareType.MISC) ? 1 : 0);

            statement.execute();
            return getPartyId(party.getName());

        } catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
            tryClose(connection);
        }

        return -1;
    }

    /**
     * Get party id from party name
     * @param partyName Name of the party to get the id for
     * @return id of the party
     */
    private int getPartyId(String partyName) {
        ResultSet resultSet = null;
        PreparedStatement statement = null;
        Connection connection = null;
        try {
            connection = getConnection(PoolIdentifier.LOAD);

            statement = connection.prepareStatement("SELECT id FROM `" + tablePrefix + "parties` WHERE party_name = ?");
            statement.setString(1, partyName);

            resultSet = statement.executeQuery();

            if (resultSet.next()){
                return resultSet.getInt("id");
            }
        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(connection);
        }
        return -1;
    }

    /**
     * Gets the id's belonging to the parties in the provided list
     * @param partyNames List of party names to get the id for
     * @return Id's of the parties that were provided
     */
    private HashMap<String, Integer> getPartyIds(List<String> partyNames) {
        HashMap<String, Integer> partyMap = new HashMap<>();

        if (partyNames.size() == 1){
            String partyName = partyNames.get(0);
            partyMap.put(partyName, getPartyId(partyName));
            return partyMap;
        }

        ResultSet resultSet = null;
        PreparedStatement statement = null;
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.LOAD);
            StringBuilder sb = new StringBuilder();

            sb.append("SELECT id,party_name FROM `").append(tablePrefix).append("parties` WHERE party_name IN (");

            for (int i = 0; i < partyNames.size() - 1; i++){
                sb.append("?,");
            }

            sb.append("?)");
            statement = connection.prepareStatement(sb.toString());

            int i = 0;
            for (String partyName : partyNames){
                i++;
                statement.setString(i, partyName);
            }

            resultSet = statement.executeQuery();

            while (resultSet.next()) {
                partyMap.put(resultSet.getString("party_name"), resultSet.getInt("id"));
            }

        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(connection);
        }

        return partyMap;
    }


    /**
     * Publicly accessible way to add a user to party
     * @param playerName Name of the player who should be added to the party
     * @param uuid Uuid of the player who should be added to the party
     * @param partyName Name of the party to add the player to
     */
    public void addUserToParty(String playerName, UUID uuid, String partyName){
        addUserToPartyGetResult(playerName, uuid, partyName);
    }

    /**
     * Adds a user to a party and returns the id they got assigned
     * @param playerName Name of the player who should be added to the party
     * @param uuid Uuid of the player who should be added to the party
     * @param partyName Name of the party to add the player to
     * @return The id of the user who got added to the party in the database
     */
    private int addUserToPartyGetResult(String playerName, UUID uuid, String partyName){
        PreparedStatement statement = null;
        Connection connection = null;
        Connection userConnection = null;
        ResultSet resultSet = null;

        try{
            connection = getConnection(PoolIdentifier.SAVE);
            userConnection = getConnection(PoolIdentifier.LOAD);

            int partyId = getPartyId(partyName);
            int userId = getUserID(userConnection, playerName, uuid);
            //TODO should be able to do this with inner joins mayb?
            statement = connection.prepareStatement("INSERT INTO `" + tablePrefix + "` (user_id, party_id) VALUES (?, ?)");

            statement.setInt(1, userId);
            statement.setInt(2, partyId);

            resultSet = statement.executeQuery();

            if (resultSet.next()){
                return resultSet.getInt("user_id");
            }

        } catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
            tryClose(connection);
            tryClose(userConnection);
            tryClose(resultSet);
        }

        return -1;
    }

    /**
     * Adds provided users to provided party
     * @param members List of members to add to the specified party
     * @param id Id of the party to add the members to
     */
    private void addUsersToParty(LinkedHashMap<UUID, String> members, int id) {
        ArrayList<Integer> userIDs = getUsersIDsNotInParty(members);
        StringBuilder query = new StringBuilder();
        query.append("INSERT INTO `").append(tablePrefix).append("party_users` (user_id, party_id) VALUES ");

        if (userIDs != null) {

            if (userIDs.size() > 1){
                for (int i = 0; i < userIDs.size() - 1; i++) {
                    query.append("(?, ?), ");
                }
            }
            query.append("(?, ?)");

            PreparedStatement statement = null;
            Connection connection = null;

            try{
                connection = getConnection(PoolIdentifier.SAVE);

                statement = connection.prepareStatement(query.toString());

                int b = 1;
                for (Integer userID : userIDs) {
                    statement.setInt(b, userID);
                    b++;
                    statement.setInt(b, id);
                    b++;
                }

                statement.execute();

            } catch (SQLException ex) {
                printErrors(ex);
            }
            finally {
                tryClose(statement);
                tryClose(connection);
            }
        }
    }

    /**
     * Get's existing users who are in the users table and in the members list but not in the party table
     * @param members List of members to check with
     * @return List of id's of users who are in the users table, and the provided members list, but not in the party users table
     */
    private ArrayList<Integer> getUsersIDsNotInParty(LinkedHashMap<UUID, String> members) {
        ResultSet resultSet = null;
        PreparedStatement statement = null;
        Connection connection = null;

        if (members.size() == 0){
            return null;
        }

        try {
            connection = getConnection(PoolIdentifier.LOAD);
            StringBuilder query = new StringBuilder();

            query.append("SELECT id FROM `").append(tablePrefix).append("users` WHERE ");

            if (members.size() == 1){
                query.append(" uuid = ? AND id NOT IN (SELECT user_id FROM `").append(tablePrefix).append("party_users`)");

                statement = connection.prepareStatement(query.toString());
                statement.setString(1, members.entrySet().iterator().next().getKey().toString());
            } else {
                for (int i = 0; i < members.size() -1; i++){
                    query.append(" uuid = ? OR");
                }
                query.append(" uuid = ? AND id NOT IN (SELECT user_id FROM `").append(tablePrefix).append("party_users`)");

                int i = 1;
                statement = connection.prepareStatement(query.toString());

                for (Map.Entry<UUID, String> entry : members.entrySet()){
                    statement.setString(i, entry.getKey().toString());
                    i++;
                }
            }

            resultSet = statement.executeQuery();
            ArrayList<Integer> userIds = new ArrayList<>();

            while (resultSet.next()){
                userIds.add(resultSet.getInt(1));
            }

            return userIds;
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(connection);
        }

        return null;
    }

    /**
     * Loads all parties from a file rather than the database
     * @param partyFile File to load parties from
     */
    private void loadPartiesFromFileAndSave(File partyFile) {
        if (!partyFile.exists()) {
            return;
        }

        if (mcMMO.getUpgradeManager().shouldUpgrade(UpgradeType.ADD_UUIDS_PARTY)) {
            PartyManager.loadAndUpgradeParties(partyFile, YamlConfiguration.loadConfiguration(partyFile));
            return;
        }

        final List<Party> parties = new ArrayList<>();

        try {
            YamlConfiguration partiesFile;
            partiesFile = YamlConfiguration.loadConfiguration(partyFile);

            ArrayList<Party> hasAlly = new ArrayList<>();

            for (String partyName : partiesFile.getConfigurationSection("").getKeys(false)) {
                Party party = new Party(partyName);

                String[] leaderSplit = partiesFile.getString(partyName + ".Leader").split("[|]");
                party.setLeader(new PartyLeader(UUID.fromString(leaderSplit[0]), leaderSplit[1]));
                party.setPassword(partiesFile.getString(partyName + ".Password"));
                party.setLocked(partiesFile.getBoolean(partyName + ".Locked"));
                party.setLevel(partiesFile.getInt(partyName + ".Level"));
                party.setXp(partiesFile.getInt(partyName + ".Xp"));

                if (partiesFile.getString(partyName + ".Ally") != null) {
                    hasAlly.add(party);
                }

                party.setXpShareMode(ShareMode.getShareMode(partiesFile.getString(partyName + ".ExpShareMode", "NONE")));
                party.setItemShareMode(ShareMode.getShareMode(partiesFile.getString(partyName + ".ItemShareMode", "NONE")));

                for (ItemShareType itemShareType : ItemShareType.values()) {
                    party.setSharingDrops(itemShareType, partiesFile.getBoolean(partyName + ".ItemShareType." + itemShareType.toString(), true));
                }

                LinkedHashMap<UUID, String> members = party.getMembers();

                for (String memberEntry : partiesFile.getStringList(partyName + ".Members")) {
                    String[] memberSplit = memberEntry.split("[|]");
                    members.put(UUID.fromString(memberSplit[0]), memberSplit[1]);
                }

                parties.add(party);
            }

            mcMMO.p.debug("Loaded (" + parties.size() + ") Parties...");

            for (Party party : hasAlly) {
                party.setAlly(PartyManager.getParty(partiesFile.getString(party.getName() + ".Ally")));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        if (!parties.isEmpty()){
            saveParties(parties);
            mcMMO.p.getLogger().info("Moving parties.yml file to parties.yml.moved since all parties were saved.");
            File newPartiesFile = new File(mcMMO.getPartyFilePath() + ".moved");
            if (!partyFile.renameTo(newPartiesFile)){
                mcMMO.p.getLogger().severe("Unable to rename parties.yml file after moving the content to the database!");
            }
        }

    }

    public List<String> getStoredUsers() {
        ArrayList<String> users = new ArrayList<>();

        Statement statement = null;
        Connection connection = null;
        ResultSet resultSet = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.createStatement();
            resultSet = statement.executeQuery("SELECT user FROM " + tablePrefix + "users");
            while (resultSet.next()) {
                users.add(resultSet.getString("user"));
            }
        }
        catch (SQLException e) {
            printErrors(e);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(connection);
        }

        return users;
    }

    /**
     * Checks that the database structure is present and correct
     */
    private void checkStructure() {

        PreparedStatement statement = null;
        Statement createStatement = null;
        ResultSet resultSet = null;
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.prepareStatement("SELECT table_name FROM INFORMATION_SCHEMA.TABLES"
                    + " WHERE table_schema = ?"
                    + " AND table_name = ?");
            statement.setString(1, Config.getInstance().getMySQLDatabaseName());
            statement.setString(2, tablePrefix + "users");
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                createStatement = connection.createStatement();
                createStatement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "users` ("
                    + "`id` int(10) unsigned NOT NULL AUTO_INCREMENT,"
                    + "`user` varchar(40) NOT NULL,"
                    + "`uuid` varchar(36) NULL DEFAULT NULL,"
                    + "`lastlogin` int(32) unsigned NOT NULL,"
                    + "PRIMARY KEY (`id`),"
                    + "INDEX(`user`(20) ASC),"
                    + "UNIQUE KEY `uuid` (`uuid`)) DEFAULT CHARSET=latin1 AUTO_INCREMENT=1;");
                tryClose(createStatement);
            }
            tryClose(resultSet);
            statement.setString(1, Config.getInstance().getMySQLDatabaseName());
            statement.setString(2, tablePrefix + "huds");
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                createStatement = connection.createStatement();
                createStatement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "huds` ("
                        + "`user_id` int(10) unsigned NOT NULL,"
                        + "`mobhealthbar` varchar(50) NOT NULL DEFAULT '" + Config.getInstance().getMobHealthbarDefault() + "',"
                        + "`scoreboardtips` int(10) NOT NULL DEFAULT '0',"
                        + "PRIMARY KEY (`user_id`)) "
                        + "DEFAULT CHARSET=latin1;");
                tryClose(createStatement);
            }
            tryClose(resultSet);
            statement.setString(1, Config.getInstance().getMySQLDatabaseName());
            statement.setString(2, tablePrefix + "cooldowns");
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                createStatement = connection.createStatement();
                createStatement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "cooldowns` ("
                        + "`user_id` int(10) unsigned NOT NULL,"
                        + "`taming` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`mining` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`woodcutting` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`repair` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`unarmed` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`herbalism` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`excavation` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`archery` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`swords` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`axes` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`acrobatics` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`blast_mining` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`chimaera_wing` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "PRIMARY KEY (`user_id`)) "
                        + "DEFAULT CHARSET=latin1;");
                tryClose(createStatement);
            }
            tryClose(resultSet);
            statement.setString(1, Config.getInstance().getMySQLDatabaseName());
            statement.setString(2, tablePrefix + "skills");
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                String startingLevel = "'" + AdvancedConfig.getInstance().getStartingLevel() + "'";
                String totalLevel = "'" + (AdvancedConfig.getInstance().getStartingLevel() * (PrimarySkillType.values().length - PrimarySkillType.CHILD_SKILLS.size())) + "'";
                createStatement = connection.createStatement();
                createStatement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "skills` ("
                        + "`user_id` int(10) unsigned NOT NULL,"
                        + "`taming` int(10) unsigned NOT NULL DEFAULT "+startingLevel+","
                        + "`mining` int(10) unsigned NOT NULL DEFAULT "+startingLevel+","
                        + "`woodcutting` int(10) unsigned NOT NULL DEFAULT "+startingLevel+","
                        + "`repair` int(10) unsigned NOT NULL DEFAULT "+startingLevel+","
                        + "`unarmed` int(10) unsigned NOT NULL DEFAULT "+startingLevel+","
                        + "`herbalism` int(10) unsigned NOT NULL DEFAULT "+startingLevel+","
                        + "`excavation` int(10) unsigned NOT NULL DEFAULT "+startingLevel+","
                        + "`archery` int(10) unsigned NOT NULL DEFAULT "+startingLevel+","
                        + "`swords` int(10) unsigned NOT NULL DEFAULT "+startingLevel+","
                        + "`axes` int(10) unsigned NOT NULL DEFAULT "+startingLevel+","
                        + "`acrobatics` int(10) unsigned NOT NULL DEFAULT "+startingLevel+","
                        + "`fishing` int(10) unsigned NOT NULL DEFAULT "+startingLevel+","
                        + "`alchemy` int(10) unsigned NOT NULL DEFAULT "+startingLevel+","
                        + "`total` int(10) unsigned NOT NULL DEFAULT "+totalLevel+","
                        + "PRIMARY KEY (`user_id`)) "
                        + "DEFAULT CHARSET=latin1;");
                tryClose(createStatement);
            }
            tryClose(resultSet);
            statement.setString(1, Config.getInstance().getMySQLDatabaseName());
            statement.setString(2, tablePrefix + "experience");
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                createStatement = connection.createStatement();
                createStatement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "experience` ("
                        + "`user_id` int(10) unsigned NOT NULL,"
                        + "`taming` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`mining` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`woodcutting` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`repair` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`unarmed` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`herbalism` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`excavation` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`archery` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`swords` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`axes` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`acrobatics` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`fishing` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`alchemy` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "PRIMARY KEY (`user_id`)) "
                        + "DEFAULT CHARSET=latin1;");
                tryClose(createStatement);
            }
            tryClose(resultSet);

            statement.setString(1, Config.getInstance().getMySQLDatabaseName());
            statement.setString(2, tablePrefix + "parties");
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                createStatement = connection.createStatement();
                createStatement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "parties` ("
                        + "`id` int(10) unsigned NOT NULL AUTO_INCREMENT, "
                        + "`party_name` varchar(256) NOT NULL, "
                        + "`owner_id` int(10) unsigned NOT NULL, "
                        + "`password` varchar(256) NULL DEFAULT NULL, "
                        + "`locked` bit NOT NULL DEFAULT b'0', "
                        + "`level` int(10) unsigned NOT NULL DEFAULT '0', "
                        + "`xp` FLOAT unsigned NOT NULL DEFAULT '0', "
                        + "`ally` int(10) unsigned NULL DEFAULT NULL, "
                        + "`exp_share_mode` varchar(16) NOT NULL DEFAULT 'NONE', "
                        + "`item_share_mode` varchar(16) NOT NULL DEFAULT 'NONE', "
                        + "`LOOT` bit NOT NULL DEFAULT b'0', "
                        + "`MINING` bit NOT NULL DEFAULT b'0', "
                        + "`HERBALISM` bit NOT NULL DEFAULT b'0', "
                        + "`WOODCUTTING` bit NOT NULL DEFAULT b'0', "
                        + "`MISC` bit NOT NULL DEFAULT b'0', "
                        + "PRIMARY KEY (`id`))"
                        + "DEFAULT CHARSET=latin1 AUTO_INCREMENT=1;");
                tryClose(createStatement);
            }
            tryClose(resultSet);

            statement.setString(1, Config.getInstance().getMySQLDatabaseName());
            statement.setString(2, tablePrefix + "party_users");
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                createStatement = connection.createStatement();
                createStatement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "party_users` ("
                        + "`user_id` int(10) unsigned NOT NULL,"
                        + "`party_id` int(10) unsigned NOT NULL,"
                        + "`ptp` BIT NOT NULL DEFAULT b'0',"
                        + "PRIMARY KEY (`user_id`),"
                        + "FOREIGN KEY (`user_id`) REFERENCES `" + tablePrefix + "users`(`id`) ON DELETE CASCADE ON UPDATE CASCADE, "
                        + "FOREIGN KEY (`party_id`) REFERENCES `" + tablePrefix + "parties`(`id`) ON DELETE CASCADE ON UPDATE CASCADE) "
                        + "DEFAULT CHARSET=latin1 AUTO_INCREMENT=1;");
                tryClose(createStatement);
            }

            tryClose(statement);

            for (UpgradeType updateType : UpgradeType.values()) {
                checkDatabaseStructure(connection, updateType);
            }

            //TODO might need to move this into UpgradeType
            File partyFile = new File(mcMMO.getPartyFilePath());
            if (partyFile.exists()){
                loadPartiesFromFileAndSave(partyFile);
            }
            //TODO end

            if (Config.getInstance().getTruncateSkills()) {
                for (PrimarySkillType skill : PrimarySkillType.NON_CHILD_SKILLS) {
                    int cap = Config.getInstance().getLevelCap(skill);
                    if (cap != Integer.MAX_VALUE) {
                        statement = connection.prepareStatement("UPDATE `" + tablePrefix + "skills` SET `" + skill.name().toLowerCase(Locale.ENGLISH) + "` = " + cap + " WHERE `" + skill.name().toLowerCase(Locale.ENGLISH) + "` > " + cap);
                        statement.executeUpdate();
                        tryClose(statement);
                    }
                }
            }

            mcMMO.p.getLogger().info("Killing orphans");
            createStatement = connection.createStatement();
            createStatement.executeUpdate("DELETE FROM `" + tablePrefix + "experience` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "users` `u` WHERE `" + tablePrefix + "experience`.`user_id` = `u`.`id`)");
            createStatement.executeUpdate("DELETE FROM `" + tablePrefix + "huds` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "users` `u` WHERE `" + tablePrefix + "huds`.`user_id` = `u`.`id`)");
            createStatement.executeUpdate("DELETE FROM `" + tablePrefix + "cooldowns` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "users` `u` WHERE `" + tablePrefix + "cooldowns`.`user_id` = `u`.`id`)");
            createStatement.executeUpdate("DELETE FROM `" + tablePrefix + "skills` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "users` `u` WHERE `" + tablePrefix + "skills`.`user_id` = `u`.`id`)");
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(createStatement);
            tryClose(connection);
        }

    }

    private Connection getConnection(PoolIdentifier identifier) throws SQLException {
        Connection connection = null;
        switch (identifier) {
            case LOAD:
                connection = loadPool.getConnection();
                break;
            case MISC:
                connection = miscPool.getConnection();
                break;
            case SAVE:
                connection = savePool.getConnection();
                break;
        }
        if (connection == null) {
            throw new RuntimeException("getConnection() for " + identifier.name().toLowerCase(Locale.ENGLISH) + " pool timed out.  Increase max connections settings.");
        }
        return connection;
    }

    /**
     * Check database structure for necessary upgrades.
     *
     * @param upgrade Upgrade to attempt to apply
     */
    private void checkDatabaseStructure(Connection connection, UpgradeType upgrade) {
        if (!mcMMO.getUpgradeManager().shouldUpgrade(upgrade)) {
            mcMMO.p.debug("Skipping " + upgrade.name() + " upgrade (unneeded)");
            return;
        }

        Statement statement = null;

        try {
            statement = connection.createStatement();

            switch (upgrade) {
                case ADD_FISHING:
                    checkUpgradeAddFishing(statement);
                    break;

                case ADD_BLAST_MINING_COOLDOWN:
                    checkUpgradeAddBlastMiningCooldown(statement);
                    break;

                case ADD_SQL_INDEXES:
                    checkUpgradeAddSQLIndexes(statement);
                    break;

                case ADD_MOB_HEALTHBARS:
                    checkUpgradeAddMobHealthbars(statement);
                    break;

                case DROP_SQL_PARTY_NAMES:
                    checkUpgradeDropPartyNames(statement);
                    break;

                case DROP_SPOUT:
                    checkUpgradeDropSpout(statement);
                    break;

                case ADD_ALCHEMY:
                    checkUpgradeAddAlchemy(statement);
                    break;

                case ADD_UUIDS:
                    checkUpgradeAddUUIDs(statement);
                    return;

                case ADD_SCOREBOARD_TIPS:
                    checkUpgradeAddScoreboardTips(statement);
                    return;

                case DROP_NAME_UNIQUENESS:
                    checkNameUniqueness(statement);
                    return;

                case ADD_SKILL_TOTAL:
                    checkUpgradeSkillTotal(connection);
                    break;
                case ADD_UNIQUE_PLAYER_DATA:
                    checkUpgradeAddUniqueChimaeraWing(statement);
                    break;

                default:
                    break;

            }

            mcMMO.getUpgradeManager().setUpgradeCompleted(upgrade);
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
        }
    }

    private void writeMissingRows(Connection connection, int id) {
        PreparedStatement statement = null;

        try {
            statement = connection.prepareStatement("INSERT IGNORE INTO " + tablePrefix + "experience (user_id) VALUES (?)");
            statement.setInt(1, id);
            statement.execute();
            statement.close();

            statement = connection.prepareStatement("INSERT IGNORE INTO " + tablePrefix + "skills (user_id) VALUES (?)");
            statement.setInt(1, id);
            statement.execute();
            statement.close();

            statement = connection.prepareStatement("INSERT IGNORE INTO " + tablePrefix + "cooldowns (user_id) VALUES (?)");
            statement.setInt(1, id);
            statement.execute();
            statement.close();

            statement = connection.prepareStatement("INSERT IGNORE INTO " + tablePrefix + "huds (user_id, mobhealthbar, scoreboardtips) VALUES (?, ?, ?)");
            statement.setInt(1, id);
            statement.setString(2, Config.getInstance().getMobHealthbarDefault().name());
            statement.setInt(3, 0);
            statement.execute();
            statement.close();
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
        }
    }

    private PlayerProfile loadFromResult(String playerName, ResultSet result) throws SQLException {
        Map<PrimarySkillType, Integer> skills = new EnumMap<>(PrimarySkillType.class); // Skill & Level
        Map<PrimarySkillType, Float> skillsXp = new EnumMap<>(PrimarySkillType.class); // Skill & XP
        Map<SuperAbilityType, Integer> skillsDATS = new EnumMap<>(SuperAbilityType.class); // Ability & Cooldown
        Map<UniqueDataType, Integer> uniqueData = new EnumMap<>(UniqueDataType.class); //Chimaera wing cooldown and other misc info
        MobHealthbarType mobHealthbarType;
        UUID uuid;
        int scoreboardTipsShown;

        final int OFFSET_SKILLS = 0; // TODO update these numbers when the query
        // changes (a new skill is added)
        final int OFFSET_XP = 13;
        final int OFFSET_DATS = 26;
        final int OFFSET_OTHER = 39;

        skills.put(PrimarySkillType.TAMING, result.getInt(OFFSET_SKILLS + 1));
        skills.put(PrimarySkillType.MINING, result.getInt(OFFSET_SKILLS + 2));
        skills.put(PrimarySkillType.REPAIR, result.getInt(OFFSET_SKILLS + 3));
        skills.put(PrimarySkillType.WOODCUTTING, result.getInt(OFFSET_SKILLS + 4));
        skills.put(PrimarySkillType.UNARMED, result.getInt(OFFSET_SKILLS + 5));
        skills.put(PrimarySkillType.HERBALISM, result.getInt(OFFSET_SKILLS + 6));
        skills.put(PrimarySkillType.EXCAVATION, result.getInt(OFFSET_SKILLS + 7));
        skills.put(PrimarySkillType.ARCHERY, result.getInt(OFFSET_SKILLS + 8));
        skills.put(PrimarySkillType.SWORDS, result.getInt(OFFSET_SKILLS + 9));
        skills.put(PrimarySkillType.AXES, result.getInt(OFFSET_SKILLS + 10));
        skills.put(PrimarySkillType.ACROBATICS, result.getInt(OFFSET_SKILLS + 11));
        skills.put(PrimarySkillType.FISHING, result.getInt(OFFSET_SKILLS + 12));
        skills.put(PrimarySkillType.ALCHEMY, result.getInt(OFFSET_SKILLS + 13));

        skillsXp.put(PrimarySkillType.TAMING, result.getFloat(OFFSET_XP + 1));
        skillsXp.put(PrimarySkillType.MINING, result.getFloat(OFFSET_XP + 2));
        skillsXp.put(PrimarySkillType.REPAIR, result.getFloat(OFFSET_XP + 3));
        skillsXp.put(PrimarySkillType.WOODCUTTING, result.getFloat(OFFSET_XP + 4));
        skillsXp.put(PrimarySkillType.UNARMED, result.getFloat(OFFSET_XP + 5));
        skillsXp.put(PrimarySkillType.HERBALISM, result.getFloat(OFFSET_XP + 6));
        skillsXp.put(PrimarySkillType.EXCAVATION, result.getFloat(OFFSET_XP + 7));
        skillsXp.put(PrimarySkillType.ARCHERY, result.getFloat(OFFSET_XP + 8));
        skillsXp.put(PrimarySkillType.SWORDS, result.getFloat(OFFSET_XP + 9));
        skillsXp.put(PrimarySkillType.AXES, result.getFloat(OFFSET_XP + 10));
        skillsXp.put(PrimarySkillType.ACROBATICS, result.getFloat(OFFSET_XP + 11));
        skillsXp.put(PrimarySkillType.FISHING, result.getFloat(OFFSET_XP + 12));
        skillsXp.put(PrimarySkillType.ALCHEMY, result.getFloat(OFFSET_XP + 13));

        // Taming - Unused - result.getInt(OFFSET_DATS + 1)
        skillsDATS.put(SuperAbilityType.SUPER_BREAKER, result.getInt(OFFSET_DATS + 2));
        // Repair - Unused - result.getInt(OFFSET_DATS + 3)
        skillsDATS.put(SuperAbilityType.TREE_FELLER, result.getInt(OFFSET_DATS + 4));
        skillsDATS.put(SuperAbilityType.BERSERK, result.getInt(OFFSET_DATS + 5));
        skillsDATS.put(SuperAbilityType.GREEN_TERRA, result.getInt(OFFSET_DATS + 6));
        skillsDATS.put(SuperAbilityType.GIGA_DRILL_BREAKER, result.getInt(OFFSET_DATS + 7));
        // Archery - Unused - result.getInt(OFFSET_DATS + 8)
        skillsDATS.put(SuperAbilityType.SERRATED_STRIKES, result.getInt(OFFSET_DATS + 9));
        skillsDATS.put(SuperAbilityType.SKULL_SPLITTER, result.getInt(OFFSET_DATS + 10));
        // Acrobatics - Unused - result.getInt(OFFSET_DATS + 11)
        skillsDATS.put(SuperAbilityType.BLAST_MINING, result.getInt(OFFSET_DATS + 12));
        uniqueData.put(UniqueDataType.CHIMAERA_WING_DATS, result.getInt(OFFSET_DATS + 13));


        try {
            mobHealthbarType = MobHealthbarType.valueOf(result.getString(OFFSET_OTHER + 1));
        }
        catch (Exception e) {
            mobHealthbarType = Config.getInstance().getMobHealthbarDefault();
        }

        try {
            scoreboardTipsShown = result.getInt(OFFSET_OTHER + 2);
        }
        catch (Exception e) {
            scoreboardTipsShown = 0;
        }

        try {
            uuid = UUID.fromString(result.getString(OFFSET_OTHER + 3));
        }
        catch (Exception e) {
            uuid = null;
        }

        return new PlayerProfile(playerName, uuid, skills, skillsXp, skillsDATS, mobHealthbarType, scoreboardTipsShown, uniqueData);
    }

    private void printErrors(SQLException ex) {
        if (debug) {
            ex.printStackTrace();
        }

        StackTraceElement element = ex.getStackTrace()[0];
        mcMMO.p.getLogger().severe("Location: " + element.getClassName() + " " + element.getMethodName() + " " + element.getLineNumber());
        mcMMO.p.getLogger().severe("SQLException: " + ex.getMessage());
        mcMMO.p.getLogger().severe("SQLState: " + ex.getSQLState());
        mcMMO.p.getLogger().severe("VendorError: " + ex.getErrorCode());
    }

    public DatabaseType getDatabaseType() {
        return DatabaseType.SQL;
    }

    private void checkNameUniqueness(final Statement statement) {
        ResultSet resultSet = null;
        try {
            resultSet = statement.executeQuery("SHOW INDEXES "
                    + "FROM `" + tablePrefix + "users` "
                    + "WHERE Column_name='user' "
                    + " AND NOT Non_unique");
            if (!resultSet.next()) {
                return;
            }
            resultSet.close();
            mcMMO.p.getLogger().info("Updating mcMMO MySQL tables to drop name uniqueness...");
            statement.execute("ALTER TABLE `" + tablePrefix + "users` " 
                    + "DROP INDEX `user`,"
                    + "ADD INDEX `user` (`user`(20) ASC)");
        } catch (SQLException ex) {
            ex.printStackTrace();
        } finally {
            tryClose(resultSet);
        }
    }

    private void checkUpgradeAddAlchemy(final Statement statement) throws SQLException {
        try {
            statement.executeQuery("SELECT `alchemy` FROM `" + tablePrefix + "skills` LIMIT 1");
        }
        catch (SQLException ex) {
            mcMMO.p.getLogger().info("Updating mcMMO MySQL tables for Alchemy...");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "skills` ADD `alchemy` int(10) NOT NULL DEFAULT '0'");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "experience` ADD `alchemy` int(10) NOT NULL DEFAULT '0'");
        }
    }

    private void checkUpgradeAddBlastMiningCooldown(final Statement statement) throws SQLException {
        try {
            statement.executeQuery("SELECT `blast_mining` FROM `" + tablePrefix + "cooldowns` LIMIT 1");
        }
        catch (SQLException ex) {
            mcMMO.p.getLogger().info("Updating mcMMO MySQL tables for Blast Mining...");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "cooldowns` ADD `blast_mining` int(32) NOT NULL DEFAULT '0'");
        }
    }

    private void checkUpgradeAddUniqueChimaeraWing(final Statement statement) throws SQLException {
        try {
            statement.executeQuery("SELECT `chimaera_wing` FROM `" + tablePrefix + "cooldowns` LIMIT 1");
        }
        catch (SQLException ex) {
            mcMMO.p.getLogger().info("Updating mcMMO MySQL tables for Chimaera Wing...");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "cooldowns` ADD `chimaera_wing` int(32) NOT NULL DEFAULT '0'");
        }
    }

    private void checkUpgradeAddFishing(final Statement statement) throws SQLException {
        try {
            statement.executeQuery("SELECT `fishing` FROM `" + tablePrefix + "skills` LIMIT 1");
        }
        catch (SQLException ex) {
            mcMMO.p.getLogger().info("Updating mcMMO MySQL tables for Fishing...");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "skills` ADD `fishing` int(10) NOT NULL DEFAULT '0'");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "experience` ADD `fishing` int(10) NOT NULL DEFAULT '0'");
        }
    }

    private void checkUpgradeAddMobHealthbars(final Statement statement) throws SQLException {
        try {
            statement.executeQuery("SELECT `mobhealthbar` FROM `" + tablePrefix + "huds` LIMIT 1");
        }
        catch (SQLException ex) {
            mcMMO.p.getLogger().info("Updating mcMMO MySQL tables for mob healthbars...");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "huds` ADD `mobhealthbar` varchar(50) NOT NULL DEFAULT '" + Config.getInstance().getMobHealthbarDefault() + "'");
        }
    }

    private void checkUpgradeAddScoreboardTips(final Statement statement) throws SQLException {
        try {
            statement.executeQuery("SELECT `scoreboardtips` FROM `" + tablePrefix + "huds` LIMIT 1");
        }
        catch (SQLException ex) {
            mcMMO.p.getLogger().info("Updating mcMMO MySQL tables for scoreboard tips...");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "huds` ADD `scoreboardtips` int(10) NOT NULL DEFAULT '0' ;");
        }
    }

    private void checkUpgradeAddSQLIndexes(final Statement statement) {
        ResultSet resultSet = null;

        try {
            resultSet = statement.executeQuery("SHOW INDEX FROM `" + tablePrefix + "skills` WHERE `Key_name` LIKE 'idx\\_%'");
            resultSet.last();

            if (resultSet.getRow() != PrimarySkillType.NON_CHILD_SKILLS.size()) {
                mcMMO.p.getLogger().info("Indexing tables, this may take a while on larger databases");

                for (PrimarySkillType skill : PrimarySkillType.NON_CHILD_SKILLS) {
                    String skill_name = skill.name().toLowerCase(Locale.ENGLISH);

                    try {
                        statement.executeUpdate("ALTER TABLE `" + tablePrefix + "skills` ADD INDEX `idx_" + skill_name + "` (`" + skill_name + "`) USING BTREE");
                    }
                    catch (SQLException ex) {
                        // Ignore
                    }
                }
            }
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
        }
    }

    private void checkUpgradeAddUUIDs(final Statement statement) {
        ResultSet resultSet = null;

        try {
            resultSet = statement.executeQuery("SELECT * FROM `" + tablePrefix + "users` LIMIT 1");

            ResultSetMetaData rsmeta = resultSet.getMetaData();
            boolean column_exists = false;

            for (int i = 1; i <= rsmeta.getColumnCount(); i++) {
                if (rsmeta.getColumnName(i).equalsIgnoreCase("uuid")) {
                    column_exists = true;
                    break;
                }
            }

            if (!column_exists) {
                mcMMO.p.getLogger().info("Adding UUIDs to mcMMO MySQL user table...");
                statement.executeUpdate("ALTER TABLE `" + tablePrefix + "users` ADD `uuid` varchar(36) NULL DEFAULT NULL");
                statement.executeUpdate("ALTER TABLE `" + tablePrefix + "users` ADD UNIQUE INDEX `uuid` (`uuid`) USING BTREE");
            }
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
        }

        new GetUUIDUpdatesRequired().runTaskLaterAsynchronously(mcMMO.p, 100); // wait until after first purge
    }

    private class GetUUIDUpdatesRequired extends BukkitRunnable {
        public void run() {
            massUpdateLock.lock();
            List<String> names = new ArrayList<>();
            Connection connection = null;
            Statement statement = null;
            ResultSet resultSet = null;
            try {
                try {
                    connection = miscPool.getConnection();
                    statement = connection.createStatement();
                    resultSet = statement.executeQuery("SELECT `user` FROM `" + tablePrefix + "users` WHERE `uuid` IS NULL");

                    while (resultSet.next()) {
                        names.add(resultSet.getString("user"));
                    }
                } catch (SQLException ex) {
                    printErrors(ex);
                } finally {
                    tryClose(resultSet);
                    tryClose(statement);
                    tryClose(connection);
                }

                if (!names.isEmpty()) {
                    UUIDUpdateAsyncTask updateTask = new UUIDUpdateAsyncTask(mcMMO.p, names);
                    updateTask.start();
                    updateTask.waitUntilFinished();
                }
            } finally {
                massUpdateLock.unlock();
            }
        }
    }

    private void checkUpgradeDropPartyNames(final Statement statement) {
        ResultSet resultSet = null;

        try {
            resultSet = statement.executeQuery("SELECT * FROM `" + tablePrefix + "users` LIMIT 1");

            ResultSetMetaData rsmeta = resultSet.getMetaData();
            boolean column_exists = false;

            for (int i = 1; i <= rsmeta.getColumnCount(); i++) {
                if (rsmeta.getColumnName(i).equalsIgnoreCase("party")) {
                    column_exists = true;
                    break;
                }
            }

            if (column_exists) {
                mcMMO.p.getLogger().info("Removing party name from users table...");
                statement.executeUpdate("ALTER TABLE `" + tablePrefix + "users` DROP COLUMN `party`");
            }
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
        }
    }

    private void checkUpgradeSkillTotal(final Connection connection) throws SQLException {
        ResultSet resultSet = null;
        Statement statement = null;

        try {
            connection.setAutoCommit(false);
            statement = connection.createStatement();
            resultSet = statement.executeQuery("SELECT * FROM `" + tablePrefix + "skills` LIMIT 1");

            ResultSetMetaData rsmeta = resultSet.getMetaData();
            boolean column_exists = false;

            for (int i = 1; i <= rsmeta.getColumnCount(); i++) {
                if (rsmeta.getColumnName(i).equalsIgnoreCase("total")) {
                    column_exists = true;
                    break;
                }
            }

            if (!column_exists) {
                mcMMO.p.getLogger().info("Adding skill total column to skills table...");
                statement.executeUpdate("ALTER TABLE `" + tablePrefix + "skills` ADD COLUMN `total` int NOT NULL DEFAULT '0'");
                statement.executeUpdate("UPDATE `" + tablePrefix + "skills` SET `total` = (taming+mining+woodcutting+repair+unarmed+herbalism+excavation+archery+swords+axes+acrobatics+fishing+alchemy)");
                statement.executeUpdate("ALTER TABLE `" + tablePrefix + "skills` ADD INDEX `idx_total` (`total`) USING BTREE");
                connection.commit();
            }
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            connection.setAutoCommit(true);
            tryClose(resultSet);
            tryClose(statement);
        }
    }

    private void checkUpgradeDropSpout(final Statement statement) {
        ResultSet resultSet = null;

        try {
            resultSet = statement.executeQuery("SELECT * FROM `" + tablePrefix + "huds` LIMIT 1");

            ResultSetMetaData rsmeta = resultSet.getMetaData();
            boolean column_exists = false;

            for (int i = 1; i <= rsmeta.getColumnCount(); i++) {
                if (rsmeta.getColumnName(i).equalsIgnoreCase("hudtype")) {
                    column_exists = true;
                    break;
                }
            }

            if (column_exists) {
                mcMMO.p.getLogger().info("Removing Spout HUD type from huds table...");
                statement.executeUpdate("ALTER TABLE `" + tablePrefix + "huds` DROP COLUMN `hudtype`");
            }
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
        }
    }

    private int getUserID(final Connection connection, final String playerName, final UUID uuid) {
        if (uuid == null)
            return getUserIDByName(connection, playerName);

        if (cachedUserIDs.containsKey(uuid))
            return cachedUserIDs.get(uuid);

        ResultSet resultSet = null;
        PreparedStatement statement = null;

        try {
            statement = connection.prepareStatement("SELECT id, user FROM " + tablePrefix + "users WHERE uuid = ? OR (uuid IS NULL AND user = ?)");
            statement.setString(1, uuid.toString());
            statement.setString(2, playerName);
            resultSet = statement.executeQuery();

            if (resultSet.next()) {
                int id = resultSet.getInt("id");

                cachedUserIDs.put(uuid, id);

                return id;
            }
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
        }

        return -1;
    }

    private int getUserIDByName(final Connection connection, final String playerName) {
        ResultSet resultSet = null;
        PreparedStatement statement = null;

        try {
            statement = connection.prepareStatement("SELECT id, user FROM " + tablePrefix + "users WHERE user = ?");
            statement.setString(1, playerName);
            resultSet = statement.executeQuery();

            if (resultSet.next()) {

                return resultSet.getInt("id");
            }
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(resultSet);
            tryClose(statement);
        }

        return -1;
    }
    
    private void tryClose(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            }
            catch (Exception e) {
                // Ignore
            }
        }
    }

    @Override
    public void onDisable() {
        mcMMO.p.debug("Releasing connection pool resource...");
        miscPool.close();
        loadPool.close();
        savePool.close();
    }

    public enum PoolIdentifier {
        MISC,
        LOAD,
        SAVE
    }

    public void resetMobHealthSettings() {
        PreparedStatement statement = null;
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.prepareStatement("UPDATE " + tablePrefix + "huds SET mobhealthbar = ?");
            statement.setString(1, Config.getInstance().getMobHealthbarDefault().toString());
            statement.executeUpdate();
        }
        catch (SQLException ex) {
            printErrors(ex);
        }
        finally {
            tryClose(statement);
            tryClose(connection);
        }
    }
}
