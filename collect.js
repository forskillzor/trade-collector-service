const fs = require('fs');
const path = require('path');

// Путь к папке с исходным кодом
const sourceDir = './src/main';


// Имя выходного файла
const outputFile = './src.txt';

// Расширения файлов, которые будем читать
const extensions = ['.kt', '.java', '.xml', '.gradle', '.properties'];

// Папки, которые нужно игнорировать
const ignoreFolders = ['res', 'resources', 'R.java', 'build', 'test', 'androidTest']; // добавь свои

// Рекурсивно собираем все файлы
function getAllFiles(dirPath, arrayOfFiles = []) {
    const files = fs.readdirSync(dirPath);

    files.forEach(file => {
        const fullPath = path.join(dirPath, file);
        const relativePath = path.relative(sourceDir, fullPath);

        // Пропускаем ненужные папки
        if (ignoreFolders.some(folder => fullPath.includes(path.join(sourceDir, folder)))) {
            return;
        }

        if (fs.statSync(fullPath).isDirectory()) {
            getAllFiles(fullPath, arrayOfFiles);
        } else {
            const ext = path.extname(file).toLowerCase();
            if (extensions.includes(ext)) {
                arrayOfFiles.push(fullPath);
            }
        }
    });

    return arrayOfFiles;
}

// Форматируем путь как относительный
function getRelativePath(filePath) {
    return path.relative(process.cwd(), filePath);
}

// Основной запуск
function main() {
    const files = getAllFiles(sourceDir);

    let output = '';

    files.forEach(filePath => {
        const relativePath = getRelativePath(filePath);
        const content = fs.readFileSync(filePath, 'utf8');

        output += `### FILE: ${relativePath}\n`;
        output += `${content}\n\n`;
    });

    fs.writeFileSync(outputFile, output, 'utf8');

    console.log(`✅ Сборка завершена. Код сохранён в ${outputFile}`);
}

main();
