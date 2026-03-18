const express = require('express');
const cors = require('cors');
const multer = require('multer');
const xlsx = require('xlsx');
const fs = require('fs');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 3000;

app.use(cors());
app.use(express.json({ limit: '10mb' }));
app.use(express.static('public'));

const DATA_DIR = path.join(__dirname, 'data');
const BACKUP_DIR = path.join(__dirname, 'backups');

[DATA_DIR, BACKUP_DIR].forEach(dir => {
    if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
    }
});

const ALLOWED_TYPES = ['students', 'exams', 'rosters', 'confirmations'];
const ALLOWED_FIELDS = {
    students: ['学号', '姓名', '性别', '班级', '联系方式'],
    exams: ['考试名称', '考试类型', '学号', '姓名', '科目', '成绩', '考试日期'],
    rosters: ['名册名称', '名册类型', '学号', '姓名', '详细信息'],
    confirmations: ['确认名称', '类型', '目标学生', '状态']
};

const storage = multer.memoryStorage();
const upload = multer({ 
    storage: storage,
    limits: { fileSize: 5 * 1024 * 1024 },
    fileFilter: (req, file, cb) => {
        const allowed = ['.xlsx', '.xls'];
        const ext = path.extname(file.originalname).toLowerCase();
        if (allowed.includes(ext)) {
            cb(null, true);
        } else {
            cb(new Error('只支持 xlsx 和 xls 格式的Excel文件'));
        }
    }
});

const getDataFilePath = (type) => path.join(DATA_DIR, `${type}.json`);
const getBackupFilePath = (filename) => path.join(BACKUP_DIR, filename);

const readData = (type) => {
    try {
        const filePath = getDataFilePath(type);
        if (fs.existsSync(filePath)) {
            const data = fs.readFileSync(filePath, 'utf-8');
            return JSON.parse(data);
        }
        return [];
    } catch (error) {
        console.error(`读取数据失败 [${type}]:`, error.message);
        return [];
    }
};

const writeData = (type, data) => {
    try {
        const filePath = getDataFilePath(type);
        fs.writeFileSync(filePath, JSON.stringify(data, null, 2), 'utf-8');
        return true;
    } catch (error) {
        console.error(`写入数据失败 [${type}]:`, error.message);
        return false;
    }
};

const validateData = (type, data) => {
    if (!ALLOWED_TYPES.includes(type)) {
        return { valid: false, message: '无效的数据类型' };
    }
    if (!Array.isArray(data)) {
        return { valid: false, message: '数据必须是数组格式' };
    }
    return { valid: true };
};

app.use((req, res, next) => {
    const start = Date.now();
    res.on('finish', () => {
        const duration = Date.now() - start;
        console.log(`[${new Date().toISOString()}] ${req.method} ${req.path} - ${res.statusCode} (${duration}ms)`);
    });
    next();
});

app.get('/api/health', (req, res) => {
    res.json({ 
        status: 'ok', 
        timestamp: new Date().toISOString(),
        version: '1.0.0'
    });
});

app.get('/api/stats', (req, res) => {
    const stats = {};
    ALLOWED_TYPES.forEach(type => {
        const data = readData(type);
        stats[type] = data.length;
    });
    res.json(stats);
});

app.get('/api/:type', (req, res) => {
    const { type } = req.params;
    if (!ALLOWED_TYPES.includes(type)) {
        return res.status(400).json({ success: false, message: '无效的数据类型' });
    }
    const data = readData(type);
    res.json(data);
});

app.post('/api/:type', (req, res) => {
    const { type } = req.params;
    const newData = req.body;
    
    const validation = validateData(type, newData);
    if (!validation.valid) {
        return res.status(400).json({ success: false, message: validation.message });
    }
    
    let existingData = readData(type);
    
    const dataWithIds = Array.isArray(newData) 
        ? newData.map((item, index) => ({ id: Date.now() + index, ...item }))
        : [{ id: Date.now(), ...newData }];
    
    existingData = [...existingData, ...dataWithIds];
    
    if (writeData(type, existingData)) {
        res.json({ success: true, data: existingData, count: dataWithIds.length });
    } else {
        res.status(500).json({ success: false, message: '保存数据失败' });
    }
});

app.put('/api/:type/:id', (req, res) => {
    const { type, id } = req.params;
    const updatedData = req.body;
    
    if (!ALLOWED_TYPES.includes(type)) {
        return res.status(400).json({ success: false, message: '无效的数据类型' });
    }
    
    let data = readData(type);
    const index = data.findIndex(item => item.id == id);
    
    if (index !== -1) {
        data[index] = { ...data[index], ...updatedData, updatedAt: new Date().toISOString() };
        if (writeData(type, data)) {
            res.json({ success: true, data: data[index] });
        } else {
            res.status(500).json({ success: false, message: '更新数据失败' });
        }
    } else {
        res.status(404).json({ success: false, message: '记录未找到' });
    }
});

app.delete('/api/:type/:id', (req, res) => {
    const { type, id } = req.params;
    
    if (!ALLOWED_TYPES.includes(type)) {
        return res.status(400).json({ success: false, message: '无效的数据类型' });
    }
    
    let data = readData(type);
    const originalLength = data.length;
    data = data.filter(item => item.id != id);
    
    if (data.length < originalLength) {
        if (writeData(type, data)) {
            res.json({ success: true, data: data, deleted: originalLength - data.length });
        } else {
            res.status(500).json({ success: false, message: '删除数据失败' });
        }
    } else {
        res.status(404).json({ success: false, message: '记录未找到' });
    }
});

app.delete('/api/:type', (req, res) => {
    const { type } = req.params;
    
    if (!ALLOWED_TYPES.includes(type)) {
        return res.status(400).json({ success: false, message: '无效的数据类型' });
    }
    
    if (writeData(type, [])) {
        res.json({ success: true, message: '数据已清空' });
    } else {
        res.status(500).json({ success: false, message: '清空数据失败' });
    }
});

app.post('/api/import/excel', upload.single('file'), (req, res) => {
    try {
        if (!req.file) {
            return res.status(400).json({ success: false, message: '请选择文件' });
        }

        const workbook = xlsx.read(req.file.buffer, { type: 'buffer' });
        const sheetName = workbook.SheetNames[0];
        const worksheet = workbook.Sheets[sheetName];
        const jsonData = xlsx.utils.sheet_to_json(worksheet);

        if (!jsonData || jsonData.length === 0) {
            return res.status(400).json({ success: false, message: '文件中没有数据' });
        }

        const dataWithIds = jsonData.map((item, index) => ({
            id: Date.now() + index,
            ...item,
            importTime: new Date().toISOString()
        }));

        res.json({ 
            success: true, 
            data: dataWithIds, 
            count: dataWithIds.length,
            fields: Object.keys(jsonData[0] || {})
        });
    } catch (error) {
        console.error('Excel导入错误:', error);
        res.status(500).json({ success: false, message: error.message || '文件解析失败' });
    }
});

app.get('/api/export/excel/:type', (req, res) => {
    try {
        const { type } = req.params;
        
        if (!ALLOWED_TYPES.includes(type)) {
            return res.status(400).json({ success: false, message: '无效的数据类型' });
        }
        
        const data = readData(type);
        
        if (data.length === 0) {
            return res.status(400).json({ success: false, message: '没有可导出的数据' });
        }
        
        const worksheet = xlsx.utils.json_to_sheet(data);
        const workbook = xlsx.utils.book_new();
        xlsx.utils.book_append_sheet(workbook, worksheet, type);
        
        const buffer = xlsx.write(workbook, { type: 'buffer', bookType: 'xlsx' });
        
        const filename = `${type}_${new Date().toISOString().slice(0, 10)}.xlsx`;
        res.setHeader('Content-Type', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
        res.setHeader('Content-Disposition', `attachment; filename=${encodeURIComponent(filename)}`);
        res.send(buffer);
    } catch (error) {
        console.error('Excel导出错误:', error);
        res.status(500).json({ success: false, message: error.message || '导出失败' });
    }
});

app.post('/api/backup', (req, res) => {
    try {
        const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
        const backupFilename = `backup_${timestamp}.json`;
        const backupData = {};
        
        ALLOWED_TYPES.forEach(type => {
            backupData[type] = readData(type);
        });
        
        fs.writeFileSync(
            getBackupFilePath(backupFilename),
            JSON.stringify(backupData, null, 2),
            'utf-8'
        );
        
        res.json({ 
            success: true, 
            message: '备份成功',
            filename: backupFilename,
            timestamp: new Date().toISOString()
        });
    } catch (error) {
        console.error('备份失败:', error);
        res.status(500).json({ success: false, message: '备份失败: ' + error.message });
    }
});

app.get('/api/backups', (req, res) => {
    try {
        const files = fs.readdirSync(BACKUP_DIR)
            .filter(f => f.endsWith('.json'))
            .map(f => {
                const stats = fs.statSync(getBackupFilePath(f));
                return {
                    filename: f,
                    size: stats.size,
                    created: stats.birthtime
                };
            })
            .sort((a, b) => new Date(b.created) - new Date(a.created));
        
        res.json(files);
    } catch (error) {
        res.status(500).json({ success: false, message: '获取备份列表失败' });
    }
});

app.post('/api/restore', (req, res) => {
    try {
        const { filename } = req.body;
        
        if (!filename) {
            return res.status(400).json({ success: false, message: '请指定备份文件名' });
        }
        
        const filepath = getBackupFilePath(filename);
        
        if (!fs.existsSync(filepath)) {
            return res.status(404).json({ success: false, message: '备份文件不存在' });
        }
        
        const backupData = JSON.parse(fs.readFileSync(filepath, 'utf-8'));
        
        Object.keys(backupData).forEach(type => {
            if (ALLOWED_TYPES.includes(type)) {
                writeData(type, backupData[type]);
            }
        });
        
        res.json({ 
            success: true, 
            message: '恢复成功',
            timestamp: new Date().toISOString()
        });
    } catch (error) {
        console.error('恢复失败:', error);
        res.status(500).json({ success: false, message: '恢复失败: ' + error.message });
    }
});

app.delete('/api/backups/:filename', (req, res) => {
    try {
        const { filename } = req.params;
        const filepath = getBackupFilePath(filename);
        
        if (!fs.existsSync(filepath)) {
            return res.status(404).json({ success: false, message: '备份文件不存在' });
        }
        
        fs.unlinkSync(filepath);
        res.json({ success: true, message: '备份文件已删除' });
    } catch (error) {
        res.status(500).json({ success: false, message: '删除备份失败' });
    }
});

app.use((err, req, res, next) => {
    console.error('服务器错误:', err);
    res.status(500).json({ 
        success: false, 
        message: err.message || '服务器内部错误'
    });
});

const server = app.listen(PORT, () => {
    console.log(`
╔═══════════════════════════════════════════════════════════╗
║         🎓 学生信息系统 v1.0.0                              ║
║═══════════════════════════════════════════════════════════║
║  🌐 访问地址: http://localhost:${PORT}                        ║
║  📁 数据目录: ${DATA_DIR}                    ║
║  💾 备份目录: ${BACKUP_DIR}                   ║
╚═══════════════════════════════════════════════════════════╝
    `);
});

process.on('SIGTERM', () => {
    console.log('收到SIGTERM信号，正在关闭服务器...');
    server.close(() => {
        console.log('服务器已关闭');
        process.exit(0);
    });
});

process.on('SIGINT', () => {
    console.log('收到SIGINT信号，正在关闭服务器...');
    server.close(() => {
        console.log('服务器已关闭');
        process.exit(0);
    });
});

module.exports = app;
