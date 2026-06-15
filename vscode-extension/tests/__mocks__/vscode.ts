const createUri = (path: string) => ({
  path,
  scheme: "file",
  fsPath: path,
  with: jest.fn().mockReturnThis(),
  toString: jest.fn().mockReturnValue(path),
});

const Uri = {
  file: jest.fn(createUri),
  parse: jest.fn(createUri),
  joinPath: jest.fn(() => createUri("/joined/path")),
};

Uri.file.mockImplementation((path: string) => createUri(path));
Uri.parse.mockImplementation((path: string) => createUri(path));

module.exports = {
  Uri,
};
