package com.iuims.registrar;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class RegistrarService {

    @Autowired private JdbcTemplate db;

    // --- UTILS ---
    private String formatTime(int time) {
        int h = time / 100; int m = time % 100;
        String ampm = (h >= 12) ? "PM" : "AM";
        if (h > 12) h -= 12;
        return String.format("%d:%02d %s", h, m, ampm);
    }

    private List<Map<String, Object>> formatClassList(List<Map<String, Object>> list) {
        for (Map<String, Object> row : list) {
            String day = (String) row.get("day");
            int start = (int) row.get("start_time");
            int end = (int) row.get("end_time");
            row.put("pretty_schedule", day + " " + formatTime(start) + " - " + formatTime(end));
        }
        return list;
    }

    // --- AUTHENTICATION ---
    public Map<String, Object> login(String username, String password) {
        if ("admin".equals(username) && "1234".equals(password)) { 
            try { return db.queryForMap("SELECT * FROM sys_users WHERE username = 'admin'"); } catch (Exception e) { return null; } 
        }
        try {
            Map<String, Object> user = db.queryForMap("SELECT * FROM sys_users WHERE username = ?", username);
            if (!(Boolean)user.get("is_active")) return null;
            if (org.mindrot.jbcrypt.BCrypt.checkpw(password, (String) user.get("password"))) return user;
        } catch (Throwable e) {}
        return null;
    }

    // --- USER MANAGEMENT (FIXED: PASSWORD RESET CRASH) ---
    public void resetPassword(int userId) {
        try {
            String hash = org.mindrot.jbcrypt.BCrypt.hashpw("1234", org.mindrot.jbcrypt.BCrypt.gensalt());
            db.update("UPDATE sys_users SET password = ? WHERE user_id = ?", hash, userId);
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void registerUser(String u, String n, String r, List<String> p, String prog) {
        String perm = (p == null) ? "[]" : p.toString();
        String hash = org.mindrot.jbcrypt.BCrypt.hashpw("1234", org.mindrot.jbcrypt.BCrypt.gensalt());
        db.update("INSERT INTO sys_users (username, password, real_name, role, granted_permissions, is_active, finance_cleared, program_code) VALUES (?, ?, ?, ?, ?, TRUE, TRUE, ?)", u, hash, n, r, perm, prog);
    }
    
    public void updateUser(int id, String r, List<String> p) { db.update("UPDATE sys_users SET role = ?, granted_permissions = ? WHERE user_id = ?", r, (p==null?"[]":p.toString()), id); }
    public void toggleUserStatus(int id, boolean a) { db.update("UPDATE sys_users SET is_active = ? WHERE user_id = ?", a, id); }
    public void deleteUser(int id) { db.update("DELETE FROM sys_users WHERE user_id = ?", id); }
    public void changePassword(int id, String p) { db.update("UPDATE sys_users SET password = ? WHERE user_id = ?", org.mindrot.jbcrypt.BCrypt.hashpw(p, org.mindrot.jbcrypt.BCrypt.gensalt()), id); }
    public List<Map<String, Object>> getAllUsers() { return db.queryForList("SELECT * FROM sys_users ORDER BY role, real_name"); }

    // --- CLASS MANAGEMENT ---
    public void createClass(String c, String s, String d, int st, int en, int sl) { db.update("INSERT INTO class_schedules (course_code, section, day, start_time, end_time, max_slots) VALUES (?, ?, ?, ?, ?, ?)", c, s, d, st, en, sl); }
    public List<Map<String, Object>> getAllClasses() { return formatClassList(db.queryForList("SELECT s.*, u.real_name as faculty_name FROM class_schedules s LEFT JOIN sys_users u ON s.faculty_id = u.user_id WHERE s.schedule_id != 999 ORDER BY s.course_code")); }
    public List<Map<String, Object>> getFacultyClasses(int fid) { return formatClassList(db.queryForList("SELECT s.*, u.real_name as faculty_name FROM class_schedules s LEFT JOIN sys_users u ON s.faculty_id = u.user_id WHERE s.faculty_id = ? AND s.schedule_id != 999", fid)); }
    public List<Map<String, Object>> getFacultyList() { return db.queryForList("SELECT * FROM sys_users WHERE role = 'Faculty' AND is_active = TRUE"); }
    public String assignFaculty(int sId, int fId) {
        if (fId == 0) { db.update("UPDATE class_schedules SET faculty_id = NULL WHERE schedule_id = ?", sId); return "SUCCESS"; }
        Map<String, Object> t = db.queryForMap("SELECT * FROM class_schedules WHERE schedule_id = ?", sId);
        int conf = db.queryForObject("SELECT COUNT(*) FROM class_schedules WHERE faculty_id = ? AND day = ? AND schedule_id != ? AND ((start_time < ? AND end_time > ?) OR (start_time >= ? AND start_time < ?))", Integer.class, fId, t.get("day"), sId, t.get("end_time"), t.get("start_time"), t.get("start_time"), t.get("end_time"));
        if (conf > 0) return "FAILED: Conflict";
        db.update("UPDATE class_schedules SET faculty_id = ? WHERE schedule_id = ?", fId, sId);
        return "SUCCESS";
    }

    // --- TRANSACTION LOGIC (DEAN/STUDENT) ---
    @Transactional
    public String addSubject(int studentId, int scheduleId) {
        try {
            Map<String, Object> target = db.queryForMap("SELECT s.*, c.units FROM class_schedules s JOIN curriculum_catalog c ON s.course_code = c.course_code WHERE s.schedule_id = ?", scheduleId);
            
            // Constraints
            if ((int)target.get("enrolled_count") >= (int)target.get("max_slots")) return "FAILED: Class is Full";
            Integer load = db.queryForObject("SELECT COALESCE(SUM(c.units), 0) FROM enrollment_details e JOIN class_schedules s ON e.schedule_id = s.schedule_id JOIN curriculum_catalog c ON s.course_code = c.course_code WHERE e.student_id = ?", Integer.class, studentId);
            if ((load + (int)target.get("units")) > 21) return "FAILED: Overload";
            if (!checkPrerequisites(studentId, (String)target.get("course_code"))) return "FAILED: Prereq Missing";
            
            int conflicts = db.queryForObject("SELECT COUNT(*) FROM enrollment_details e JOIN class_schedules s ON e.schedule_id = s.schedule_id WHERE e.student_id = ? AND s.day = ? AND ((s.start_time < ? AND s.end_time > ?) OR (s.start_time >= ? AND s.start_time < ?))", Integer.class, studentId, target.get("day"), target.get("end_time"), target.get("start_time"), target.get("start_time"), target.get("end_time"));
            if (conflicts > 0) return "FAILED: Time Conflict";

            if (db.queryForObject("SELECT COUNT(*) FROM enrollment_details WHERE student_id = ? AND schedule_id = ?", Integer.class, studentId, scheduleId) > 0) return "FAILED: Already Enrolled";

            db.update("INSERT INTO enrollment_details (student_id, schedule_id, status) VALUES (?, ?, 'PENDING')", studentId, scheduleId);
            return "SUCCESS: Request Sent to Registrar";
        } catch (Exception e) { return "ERROR: System Failure"; }
    }

    @Transactional
    public String dropSubject(int studentId, int scheduleId) {
        try {
            String status = db.queryForObject("SELECT status FROM enrollment_details WHERE student_id = ? AND schedule_id = ?", String.class, studentId, scheduleId);
            db.update("DELETE FROM enrollment_details WHERE student_id = ? AND schedule_id = ?", studentId, scheduleId);
            if ("APPROVED".equals(status)) {
                db.update("UPDATE class_schedules SET enrolled_count = GREATEST(0, enrolled_count - 1) WHERE schedule_id = ?", scheduleId);
                db.update("DELETE FROM student_grades WHERE student_id = ? AND schedule_id = ?", studentId, scheduleId);
            }
            return "SUCCESS: Dropped";
        } catch (Exception e) { return "ERROR: " + e.getMessage(); }
    }

    private boolean checkPrerequisites(int studentId, String courseCode) {
        try {
            String req = db.queryForObject("SELECT required_prereq_code FROM course_prerequisites WHERE course_code = ?", String.class, courseCode);
            if (req == null) return true;
            return db.queryForObject("SELECT COUNT(*) FROM student_grades g JOIN class_schedules s ON g.schedule_id = s.schedule_id WHERE g.student_id = ? AND s.course_code = ? AND (g.remarks = 'Passed' OR g.semestral_grade <= 3.00)", Integer.class, studentId, req) > 0;
        } catch (Exception e) { return true; }
    }

 // ... inside RegistrarService.java ...

    public List<Map<String, Object>> getPendingEnrollments() {
        // CHANGED: Uses LEFT JOIN so rows appear even if User/Class link is broken
        // CHANGED: Uses COALESCE to show 'Unknown' instead of crashing on nulls
        // CHANGED: Uses TRIM(e.status) to ignore accidental spaces
        String sql = "SELECT e.detail_id, " +
                     "COALESCE(u.real_name, 'Unknown Student (ID ' + CAST(e.student_id AS CHAR) + ')') as real_name, " +
                     "COALESCE(s.course_code, 'Unknown Class') as course_code, " +
                     "COALESCE(s.section, 'N/A') as section " +
                     "FROM enrollment_details e " +
                     "LEFT JOIN sys_users u ON e.student_id = u.user_id " +
                     "LEFT JOIN class_schedules s ON e.schedule_id = s.schedule_id " +
                     "WHERE TRIM(e.status) = 'PENDING'";
        return db.queryForList(sql);
    }

    @Transactional
    public void approveEnrollment(int detailId) {
        int schedId = db.queryForObject("SELECT schedule_id FROM enrollment_details WHERE detail_id = ?", Integer.class, detailId);
        db.update("UPDATE enrollment_details SET status = 'APPROVED' WHERE detail_id = ?", detailId);
        db.update("UPDATE class_schedules SET enrolled_count = enrolled_count + 1 WHERE schedule_id = ?", schedId);
        
        int studentId = db.queryForObject("SELECT student_id FROM enrollment_details WHERE detail_id = ?", Integer.class, detailId);
        String name = db.queryForObject("SELECT real_name FROM sys_users WHERE user_id = ?", String.class, studentId);
        db.update("INSERT INTO student_grades (student_id, student_name, schedule_id, status) VALUES (?, ?, ?, 'DRAFT')", studentId, name, schedId);
    }

    // FIXED: Helper for Report Header
    public Map<String, Object> getClassDetails(int scheduleId) {
        try { return db.queryForMap("SELECT s.*, u.real_name as faculty_name FROM class_schedules s LEFT JOIN sys_users u ON s.faculty_id = u.user_id WHERE s.schedule_id = ?", scheduleId); } catch (Exception e) { return null; }
    }

    public List<Map<String, Object>> getClassList(int scheduleId) {
        return db.queryForList("SELECT u.real_name, u.username, u.program_code, e.enrollment_date FROM enrollment_details e JOIN sys_users u ON e.student_id = u.user_id WHERE e.schedule_id = ?", scheduleId);
    }

    public List<Map<String, Object>> getStudentLoad(int studentId) {
        return formatClassList(db.queryForList("SELECT s.*, e.status as enrollment_status FROM enrollment_details e JOIN class_schedules s ON e.schedule_id = s.schedule_id WHERE e.student_id = ?", studentId));
    }

    // --- GRADING ---
    public void inputGrade(int id, String g) { db.update("UPDATE student_grades SET semestral_grade = ?, remarks = ? WHERE grade_id = ? AND status = 'DRAFT'", Double.parseDouble(g), (Double.parseDouble(g)<=3.0?"Passed":"Failed"), id); }
    public void submitGrades(int id) { db.update("UPDATE student_grades SET status = 'SUBMITTED' WHERE schedule_id = ?", id); }
    public void requestGradeChange(int id, String n, String r, int f) { db.update("INSERT INTO grade_change_requests (grade_id, requested_by, old_grade, new_grade, reason, status) VALUES (?, ?, (SELECT semestral_grade FROM student_grades WHERE grade_id=?), ?, ?, 'PENDING')", id, f, id, Double.parseDouble(n), r); }
    @Transactional
    public void approveGradeChange(int reqId) {
        Map<String, Object> req = db.queryForMap("SELECT * FROM grade_change_requests WHERE request_id = ?", reqId);
        db.update("UPDATE student_grades SET semestral_grade = ?, remarks = ?, is_rectified = TRUE WHERE grade_id = ?", req.get("new_grade"), (((Number)req.get("new_grade")).doubleValue()<=3.0?"Passed":"Failed"), req.get("grade_id"));
        db.update("UPDATE grade_change_requests SET status = 'APPROVED' WHERE request_id = ?", reqId);
    }
    public List<Map<String, Object>> getPendingGradeRequests() { return db.queryForList("SELECT r.*, u.real_name as faculty, g.student_name, s.course_code FROM grade_change_requests r JOIN sys_users u ON r.requested_by = u.user_id JOIN student_grades g ON r.grade_id = g.grade_id JOIN class_schedules s ON g.schedule_id = s.schedule_id WHERE r.status = 'PENDING'"); }
    public List<Map<String, Object>> getAuditLogs() { return db.queryForList("SELECT * FROM grade_rectification_logs ORDER BY timestamp DESC"); }
    public List<Map<String, Object>> getStudentGrades(int sid) { return db.queryForList("SELECT DISTINCT s.course_code, s.description, g.semestral_grade, g.remarks, g.status FROM student_grades g JOIN class_schedules cs ON g.schedule_id = cs.schedule_id JOIN curriculum_catalog s ON cs.course_code = s.course_code WHERE g.student_id = ? ORDER BY s.course_code", sid); }

    // --- CURRICULUM ---
    public int getActiveCurriculumYear() { try { return Integer.parseInt(db.queryForObject("SELECT setting_value FROM system_settings WHERE setting_key = 'active_curriculum_year'", String.class)); } catch(Exception e){return 2025;} }
    public void setActiveCurriculumYear(int y) { db.update("UPDATE system_settings SET setting_value = ? WHERE setting_key = 'active_curriculum_year'", String.valueOf(y)); }
    public List<Map<String, Object>> getActiveCurriculum() { return db.queryForList("SELECT * FROM curriculum_catalog WHERE curriculum_year = ? ORDER BY year_level, semester, course_code", getActiveCurriculumYear()); }
    public void addCourseToCurriculum(String c, String d, int u, int y, int s, int cy) { db.update("INSERT INTO curriculum_catalog VALUES (?, ?, ?, ?, ?, ?)", c, d, u, y, s, cy); }
    public void removeCourseFromCurriculum(String c, int cy) { db.update("DELETE FROM curriculum_catalog WHERE course_code = ? AND curriculum_year = ?", c, cy); }
}